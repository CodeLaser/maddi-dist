/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import { AnalyzeConfig, ClasspathEntry, ModuleSourceSet, SourceRoot } from './analysisModel';

/**
 * Builds maddi's `AnalyzeConfig` from the Java project model, the counterpart of `MaddiConfigBuilder`
 * (IntelliJ) and `MaddiEclipseConfigBuilder` (Eclipse).
 *
 * VS Code has no project model of its own, which was the open question for this front-end: the answer is to
 * ask **jdt.ls**, the language server behind `redhat.java`. It already knows the classpath, and — the part
 * that actually matters — it *compiles*, so the `.class` files the openjdk parser needs exist on disk and
 * stay fresh. That is the same trick the other two front-ends use (IntelliJ's and JDT's compiler output
 * directories); here the compiler simply belongs to another extension.
 *
 * jdt.ls is reached through `java.execute.workspaceCommand`, which delegates to a server-side command. The
 * ones used here, with their real shapes (jdt.ls 1.59):
 *
 * - `java.project.getAll` -> project root URIs (string[])
 * - `java.project.getClasspaths(uri, {scope})` -> `{projectRoot, classpaths[], modulepaths[]}`
 * - `java.project.listSourcePaths` -> `{data: [{path, displayPath, classpathEntry, projectName, projectType}]}`
 * - `java.project.getSettings(uri, keys[])` -> `{key: value}`; for {@link CLASSPATH_ENTRIES} the project's raw
 *   class path, `[{kind, path, output, attributes}]`, with containers (Maven, Gradle) expanded and the JRE left out
 */

/**
 * Everything this module needs from VS Code, so the mapping can be tested without a language server — and
 * the reason this file imports no `vscode`: that module only exists inside the extension host, so anything
 * touching it cannot be unit-tested at all.
 */
export type ExecuteCommand = (command: string, ...args: unknown[]) => Promise<unknown>;

/**
 * jdt.ls keeps a synthetic project for files that belong to no real one. `java.project.getAll` reports it
 * like any other, and it has nothing to analyse.
 */
export const DEFAULT_PROJECT_NAME = 'jdt.ls-java-project';

export function isDefaultProject(projectUri: string): boolean {
    return projectUri.endsWith(DEFAULT_PROJECT_NAME);
}

export interface ClasspathResult {
    projectRoot: string;
    classpaths: string[];
    modulepaths: string[];
}

export interface SourcePath {
    path: string;
    displayPath: string;
    classpathEntry?: string;
    projectName: string;
    projectType?: string;
}

/** JDK modules loaded by default; the same list the IntelliJ and Eclipse front-ends use. */
export const DEFAULT_JMODS = [
    'java.base', 'java.logging', 'java.xml', 'java.sql', 'java.naming',
    'java.desktop', 'java.management', 'java.net.http', 'java.compiler',
];

const WORKSPACE_COMMAND = 'java.execute.workspaceCommand';

export interface BuildOptions {
    /** Home of the maddi JDK (25+): the analysis SDK, NOT the JDK the project targets. */
    jdkHome: string;
    warnNearMisses: boolean;
    /** Absolute path of the folder being analysed; becomes the working directory. */
    workingDirectory: string;
}

/**
 * The project model as maddi's per-module source sets when jdt.ls can say enough for them, else the flat form.
 *
 * The source sets are what the IntelliJ and Eclipse front-ends send: each project a main set and a test set, each
 * with its own class path and the main sets of the projects it requires. The flat form makes the daemon wire every
 * source root to every class-path entry, which on a multi-module workspace is many times what javac opens, puts
 * test-only libraries in reach of main code, and makes a project's own classes reachable both as source and as
 * bytecode through another project's output folder.
 */
export async function buildConfig(execute: ExecuteCommand, options: BuildOptions): Promise<AnalyzeConfig> {
    const all = (await execute(WORKSPACE_COMMAND, 'java.project.getAll')) as string[] | undefined ?? [];
    // jdt.ls reports its synthetic catch-all project alongside the real ones; it has nothing to analyse
    const projects = all.filter((uri) => !isDefaultProject(uri));
    const sourcePaths = await listSourcePaths(execute);

    const sets = await moduleSourceSets(execute, projects, sourcePaths);
    if (sets) {
        return {
            workingDirectory: options.workingDirectory,
            sdkHome: options.jdkHome,
            sourceEncoding: 'UTF-8',
            jmods: DEFAULT_JMODS,
            sources: [],   // the flat pair stays empty: the sets carry the structure
            classpath: [],
            restrictToPackages: [],
            parallel: true,
            warnNearMisses: options.warnNearMisses,
            moduleSourceSets: sets,
        };
    }
    return flatConfig(execute, projects, sourcePaths, options);
}

/**
 * The flat form, for a workspace the source sets cannot describe: one union class path, and every source root on
 * its own. What this front-end sent before 0.9.6.
 */
async function flatConfig(execute: ExecuteCommand, projects: string[], sourcePaths: SourcePath[],
                          options: BuildOptions): Promise<AnalyzeConfig> {
    // path -> scope, deduplicated and order-preserving, as in the other front-ends
    const classpath = new Map<string, string>();
    for (const project of projects) {
        // Two scopes, and the difference between them is the point: the runtime classpath is what compiles
        // and runs the main code, so anything the test scope adds on top is test-only. Asking once with the
        // wider scope would flatten that distinction and analyse test dependencies as production ones.
        const compile = await classpathsOf(execute, project, 'runtime');
        for (const entry of compile) classpath.set(entry, 'compile');
        const test = await classpathsOf(execute, project, 'test');
        for (const entry of test) if (!classpath.has(entry)) classpath.set(entry, 'test');
    }

    const sources: SourceRoot[] = sourcePaths.map((sp) => ({
        name: sp.displayPath || sp.path,
        path: sp.path,
        test: looksLikeTestRoot(sp.path),
    }));

    const classpathEntries: ClasspathEntry[] = [...classpath.entries()].map(([path, scope]) => ({ path, scope }));

    return {
        workingDirectory: options.workingDirectory,
        sdkHome: options.jdkHome,
        sourceEncoding: 'UTF-8',
        jmods: DEFAULT_JMODS,
        sources,
        classpath: classpathEntries,
        restrictToPackages: [],
        parallel: true,
        warnNearMisses: options.warnNearMisses,
    };
}

/** `IClasspathEntry` kinds, as jdt.ls reports them. */
export const CPE_LIBRARY = 1;
export const CPE_PROJECT = 2;
export const CPE_SOURCE = 3;

export const CLASSPATH_ENTRIES = 'org.eclipse.jdt.ls.core.classpathEntries';
export const OUTPUT_PATH = 'org.eclipse.jdt.ls.core.outputPath';
export const COMPILER_RELEASE = 'org.eclipse.jdt.core.compiler.release';
export const COMPILER_COMPLIANCE = 'org.eclipse.jdt.core.compiler.compliance';

/** One raw class-path entry of a project, as `java.project.getSettings` reports it. */
export interface ProjectClasspathEntry {
    kind: number;
    /** A source folder's or library's absolute path; a project's `/<name>`. */
    path: string;
    /** A source folder's own output folder, absolute; absent when it uses the project's. */
    output?: string;
    /** `test: "true"` marks a test source folder, or a library only the tests use. */
    attributes?: Record<string, string>;
}

interface ProjectModel {
    uri: string;
    name: string;
    entries: ProjectClasspathEntry[];
    outputPath?: string;
    release: number;
}

/**
 * The counterpart of `MaddiEclipseConfigBuilder.addSets`, on what jdt.ls — which is Eclipse JDT — reports of each
 * project's raw class path. Undefined when that is not enough to describe the workspace, and the flat form must do:
 * a jdt.ls without `getSettings`, a project it gives no class path for, or a source root no set covers (jdt.ls drops
 * a source folder that is the project's root folder itself, and an analysis that silently skipped it would be worse
 * than the flat one).
 */
export async function moduleSourceSets(execute: ExecuteCommand, projects: string[], sourcePaths: SourcePath[]):
    Promise<ModuleSourceSet[] | undefined> {
    const models: ProjectModel[] = [];
    for (const uri of projects) {
        let settings: Record<string, unknown> | undefined;
        try {
            settings = (await execute(WORKSPACE_COMMAND, 'java.project.getSettings', uri,
                [CLASSPATH_ENTRIES, OUTPUT_PATH, COMPILER_RELEASE, COMPILER_COMPLIANCE])) as
                Record<string, unknown> | undefined;
        } catch {
            return undefined;
        }
        const entries = settings?.[CLASSPATH_ENTRIES];
        if (!Array.isArray(entries)) return undefined;
        const typed = entries as ProjectClasspathEntry[];
        const sources = typed.filter((e) => e.kind === CPE_SOURCE).map((e) => normalize(e.path));
        // jdt.ls knows a project by its URI here and by its name in a project entry; listSourcePaths has both a
        // source root's path and its project's name, which ties the two together
        const name = sourcePaths.find((sp) => sources.includes(normalize(sp.path)))?.projectName
            ?? lastSegment(uri);
        const output = settings?.[OUTPUT_PATH];
        models.push({
            uri, name, entries: typed,
            outputPath: typeof output === 'string' && output.length > 0 ? output : undefined,
            release: apiRelease(settings?.[COMPILER_RELEASE], settings?.[COMPILER_COMPLIANCE]),
        });
    }

    const sets: ModuleSourceSet[] = [];
    for (const model of models) addSets(model, sets);
    const mainSets = new Set(sets.filter((s) => !s.test).map((s) => s.name));
    for (const set of sets) set.dependencies = set.dependencies.filter((d) => mainSets.has(d));

    const covered = new Set(sets.flatMap((s) => s.sourceDirectories.map(normalize)));
    const uncovered = sourcePaths.some((sp) => sp.projectName !== DEFAULT_PROJECT_NAME
        && !covered.has(normalize(sp.path)));
    if (sets.length === 0 || uncovered) return undefined;
    return sets;
}

function addSets(model: ProjectModel, sets: ModuleSourceSet[]): void {
    const main: string[] = [];
    const test: string[] = [];
    let mainOutput: string | undefined;
    let testOutput: string | undefined;
    // path -> whether ONLY tests use it; a library on both class paths counts as main
    const libraries = new Map<string, boolean>();
    const required: string[] = [];
    for (const entry of model.entries) {
        const isTest = entry.attributes?.test === 'true';
        if (entry.kind === CPE_SOURCE) {
            // a source folder's own output overrides the project default (e.g. test sources -> bin/test)
            const out = entry.output || model.outputPath;
            if (isTest) {
                test.push(entry.path);
                testOutput ??= out;
            } else {
                main.push(entry.path);
                mainOutput ??= out;
            }
        } else if (entry.kind === CPE_LIBRARY) {
            const previous = libraries.get(entry.path);
            libraries.set(entry.path, previous === undefined ? isTest : previous && isTest);
        } else if (entry.kind === CPE_PROJECT) {
            const name = entry.path.replace(/^[/\\]+/, '');
            if (name.length > 0 && !required.includes(name)) required.push(name);
        }
    }
    if (main.length === 0 && test.length === 0) return; // no Java sources of its own

    const mainClassPath: string[] = [];
    const testClassPath: string[] = [];
    for (const [path, testOnly] of libraries) {
        if (!testOnly) mainClassPath.push(path);
        testClassPath.push(path);
    }
    const dependencies = required.map(mainSetName);
    if (main.length > 0) {
        sets.push({
            name: mainSetName(model.name), sourceDirectories: main, outputPath: mainOutput, test: false,
            sourceRelease: model.release, dependencies, classPath: mainClassPath,
        });
    }
    if (test.length > 0) {
        sets.push({
            name: testSetName(model.name), sourceDirectories: test, outputPath: testOutput, test: true,
            sourceRelease: model.release,
            dependencies: main.length > 0 ? [...dependencies, mainSetName(model.name)] : [...dependencies],
            classPath: testClassPath,
        });
    }
}

export function mainSetName(project: string): string {
    return `${project}/main`;
}

export function testSetName(project: string): string {
    return `${project}/test`;
}

/**
 * The release whose API the project compiles against, or 0 for "not known", which the daemon reads as "the JDK maddi
 * runs on". As in the Eclipse front-end: only with `--release` on (m2e sets it from `maven.compiler.release`) is the
 * compliance level an API release; without it the project compiles against the JRE it is bound to.
 */
export function apiRelease(release: unknown, compliance: unknown): number {
    if (release !== 'enabled' || typeof compliance !== 'string') return 0;
    const version = compliance.startsWith('1.') ? compliance.substring(2) : compliance;
    const n = Number.parseInt(version, 10);
    return Number.isNaN(n) ? 0 : n;
}

function normalize(path: string): string {
    return path.replace(/\\/g, '/').replace(/\/+$/, '');
}

function lastSegment(uri: string): string {
    const trimmed = uri.replace(/\/+$/, '');
    return decodeURIComponent(trimmed.substring(trimmed.lastIndexOf('/') + 1));
}

async function classpathsOf(execute: ExecuteCommand, projectUri: string, scope: string): Promise<string[]> {
    // The options go over as a JSON STRING, not as an object. jdt.ls deserializes this argument with
    // JSONUtility.toModel, which handles a JsonElement or a String and returns null for anything else — and
    // a plain object arrives as a Map, so it silently becomes null and the server throws an NPE on
    // options.scope. vscode-java's own API does the same JSON.stringify; this is not optional.
    const result = (await execute(WORKSPACE_COMMAND, 'java.project.getClasspaths', projectUri,
        JSON.stringify({ scope }))) as ClasspathResult | undefined;
    if (!result) return [];
    // modulepaths matter as much as classpaths for a modular project: on one, the dependencies are on the
    // module path and `classpaths` alone would be nearly empty.
    return [...(result.classpaths ?? []), ...(result.modulepaths ?? [])];
}

async function listSourcePaths(execute: ExecuteCommand): Promise<SourcePath[]> {
    const result = (await execute(WORKSPACE_COMMAND, 'java.project.listSourcePaths')) as
        { data?: SourcePath[] } | undefined;
    return result?.data ?? [];
}

/**
 * Whether a source root holds tests.
 *
 * jdt.ls's `listSourcePaths` does not say, so this goes on layout convention, which covers Maven and Gradle
 * (`src/test/java`, `src/integrationTest/…`) and the common plain layouts. It is the one guess in this
 * mapping; getting it wrong mis-scopes a root rather than losing it, so the analysis still runs.
 */
export function looksLikeTestRoot(sourcePath: string): boolean {
    const normalized = sourcePath.replace(/\\/g, '/');
    return /(^|\/)(test|tests)(\/|$)/i.test(normalized) || /\/src\/[^/]*[Tt]est[^/]*\//.test(normalized);
}
