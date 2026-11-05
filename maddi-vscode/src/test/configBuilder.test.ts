/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as assert from 'assert';
import { test } from 'node:test';
import { CLASSPATH_ENTRIES, COMPILER_COMPLIANCE, COMPILER_RELEASE, OUTPUT_PATH, apiRelease, buildConfig, looksLikeTestRoot }
    from '../configBuilder';

/**
 * The jdt.ls project model to maddi's AnalyzeConfig. Driven with canned responses in the exact shapes
 * jdt.ls 1.59 returns, so the mapping is pinned without a language server: getting it wrong produces an
 * analysis of nothing rather than an error, which is the failure mode worth guarding.
 */

const PROJECT = 'file:///w/app';

function jdtls(responses: Record<string, unknown>) {
    const calls: string[] = [];
    const execute = async (command: string, ...args: unknown[]): Promise<unknown> => {
        const delegate = String(args[0]);
        calls.push(delegate);
        if (command !== 'java.execute.workspaceCommand') throw new Error(`unexpected bridge ${command}`);
        if (delegate === 'java.project.getClasspaths') {
            // jdt.ls deserializes this argument with JSONUtility.toModel, which accepts a JsonElement or a
            // String and yields null for anything else — so an object here means a server-side NPE. The
            // harness insists on the string to keep that contract, having already been fooled by it once.
            assert.strictEqual(typeof args[2], 'string',
                'the classpath options must be passed as a JSON string, not an object');
            const scope = (JSON.parse(args[2] as string) as { scope: string }).scope;
            return responses[`getClasspaths:${scope}`];
        }
        if (delegate === 'java.project.getSettings') {
            // vscode-java's own getProjectSettings passes the keys as a plain array; jdt.ls casts it to ArrayList
            assert.ok(Array.isArray(args[2]), 'the settings keys must be passed as an array');
            return responses[`getSettings:${String(args[1])}`];
        }
        return responses[delegate];
    };
    return { execute, calls };
}

const OPTIONS = { jdkHome: '/opt/jdk-25', warnNearMisses: false, workingDirectory: '/w' };

test('compiler output directories become classpath entries', async () => {
    const { execute } = jdtls({
        'java.project.getAll': [PROJECT],
        'getClasspaths:runtime': {
            projectRoot: PROJECT,
            classpaths: ['/w/app/target/classes', '/home/me/.m2/guava.jar'],
            modulepaths: [],
        },
        'getClasspaths:test': { projectRoot: PROJECT, classpaths: [], modulepaths: [] },
        'java.project.listSourcePaths': { data: [] },
    });
    const config = await buildConfig(execute, OPTIONS);

    // this is the whole point of using jdt.ls: it compiles, so the .class files the openjdk parser needs
    // are on disk, and they reach maddi as ordinary classpath entries
    const output = config.classpath.find((c) => c.path === '/w/app/target/classes');
    assert.ok(output, 'the compiler output dir must be on the classpath');
    assert.strictEqual(output.scope, 'compile');
    assert.ok(config.classpath.some((c) => c.path.endsWith('guava.jar')), 'libraries too');
});

test('what the test scope adds beyond runtime is scoped as test', async () => {
    const { execute } = jdtls({
        'java.project.getAll': [PROJECT],
        'getClasspaths:runtime': { projectRoot: PROJECT, classpaths: ['/w/app/target/classes'], modulepaths: [] },
        'getClasspaths:test': {
            projectRoot: PROJECT,
            classpaths: ['/w/app/target/classes', '/w/app/target/test-classes', '/home/me/.m2/junit.jar'],
            modulepaths: [],
        },
        'java.project.listSourcePaths': { data: [] },
    });
    const config = await buildConfig(execute, OPTIONS);

    const scopeOf = (path: string) => config.classpath.find((c) => c.path === path)?.scope;
    assert.strictEqual(scopeOf('/w/app/target/classes'), 'compile', 'shared entries stay compile-scoped');
    assert.strictEqual(scopeOf('/w/app/target/test-classes'), 'test');
    assert.strictEqual(scopeOf('/home/me/.m2/junit.jar'), 'test');
});

test('module paths are included: on a modular project the classpath is nearly empty', async () => {
    const { execute } = jdtls({
        'java.project.getAll': [PROJECT],
        'getClasspaths:runtime': {
            projectRoot: PROJECT,
            classpaths: [],
            modulepaths: ['/w/app/target/classes', '/home/me/.m2/some.jar'],
        },
        'getClasspaths:test': { projectRoot: PROJECT, classpaths: [], modulepaths: [] },
        'java.project.listSourcePaths': { data: [] },
    });
    const config = await buildConfig(execute, OPTIONS);
    assert.strictEqual(config.classpath.length, 2, 'modulepath entries must not be dropped');
});

test("jdt.ls's synthetic catch-all project is skipped", async () => {
    const { execute, calls } = jdtls({
        'java.project.getAll': ['file:///w/jdt.ls-java-project', PROJECT],
        'getClasspaths:runtime': { projectRoot: PROJECT, classpaths: ['/w/app/bin'], modulepaths: [] },
        'getClasspaths:test': { projectRoot: PROJECT, classpaths: [], modulepaths: [] },
        'java.project.listSourcePaths': { data: [] },
    });
    await buildConfig(execute, OPTIONS);
    // one real project, asked twice (runtime + test); the synthetic one is never queried
    assert.strictEqual(calls.filter((c) => c === 'java.project.getClasspaths').length, 2);
});

test('source roots come through, with test roots marked', async () => {
    const { execute } = jdtls({
        'java.project.getAll': [PROJECT],
        'getClasspaths:runtime': { projectRoot: PROJECT, classpaths: [], modulepaths: [] },
        'getClasspaths:test': { projectRoot: PROJECT, classpaths: [], modulepaths: [] },
        'java.project.listSourcePaths': {
            data: [
                { path: '/w/app/src/main/java', displayPath: 'app/src/main/java', projectName: 'app' },
                { path: '/w/app/src/test/java', displayPath: 'app/src/test/java', projectName: 'app' },
            ],
        },
    });
    const config = await buildConfig(execute, OPTIONS);
    assert.strictEqual(config.sources.length, 2);
    assert.strictEqual(config.sources[0].test, false);
    assert.strictEqual(config.sources[1].test, true);
});

test('an empty workspace yields a config rather than throwing', async () => {
    const { execute } = jdtls({ 'java.project.getAll': undefined, 'java.project.listSourcePaths': undefined });
    const config = await buildConfig(execute, OPTIONS);
    assert.deepStrictEqual(config.sources, []);
    assert.deepStrictEqual(config.classpath, []);
    // the analysis SDK is maddi's own JDK, never the project's
    assert.strictEqual(config.sdkHome, '/opt/jdk-25');
});

// ---- per-module source sets, from java.project.getSettings ----

const APP = 'file:///w/app';
const LIB = 'file:///w/lib';
const TEST = { test: 'true' };

/** A two-project Maven workspace: app requires lib (as m2e reports it, a project entry inside its container). */
function twoProjects(overrides: Record<string, unknown> = {}) {
    return jdtls({
        'java.project.getAll': [APP, LIB],
        'java.project.listSourcePaths': {
            data: [
                { path: '/w/app/src/main/java', displayPath: 'app/src/main/java', projectName: 'app' },
                { path: '/w/app/src/test/java', displayPath: 'app/src/test/java', projectName: 'app' },
                { path: '/w/lib/src/main/java', displayPath: 'lib/src/main/java', projectName: 'lib' },
            ],
        },
        [`getSettings:${APP}`]: {
            [CLASSPATH_ENTRIES]: [
                { kind: 3, path: '/w/app/src/main/java', output: '/w/app/target/classes', attributes: {} },
                { kind: 3, path: '/w/app/src/test/java', output: '/w/app/target/test-classes', attributes: TEST },
                { kind: 2, path: '/lib', attributes: {} },
                { kind: 1, path: '/m2/guava.jar', attributes: {} },
                { kind: 1, path: '/m2/junit.jar', attributes: TEST },
            ],
            [OUTPUT_PATH]: '/w/app/target/classes',
            [COMPILER_RELEASE]: 'enabled',
            [COMPILER_COMPLIANCE]: '21',
        },
        [`getSettings:${LIB}`]: {
            [CLASSPATH_ENTRIES]: [
                { kind: 3, path: '/w/lib/src/main/java', attributes: {} },
                { kind: 1, path: '/m2/guava.jar', attributes: {} },
            ],
            [OUTPUT_PATH]: '/w/lib/target/classes',
            [COMPILER_RELEASE]: 'disabled',
            [COMPILER_COMPLIANCE]: '17',
        },
        ...overrides,
    });
}

test('each project gives a main and a test set, the flat pair stays empty', async () => {
    const { execute } = twoProjects();
    const config = await buildConfig(execute, OPTIONS);
    assert.deepStrictEqual(config.sources, []);
    assert.deepStrictEqual(config.classpath, []);
    assert.deepStrictEqual(config.moduleSourceSets, [
        {
            name: 'app/main', sourceDirectories: ['/w/app/src/main/java'], outputPath: '/w/app/target/classes',
            test: false, sourceRelease: 21, dependencies: ['lib/main'], classPath: ['/m2/guava.jar'],
        },
        {
            name: 'app/test', sourceDirectories: ['/w/app/src/test/java'], outputPath: '/w/app/target/test-classes',
            test: true, sourceRelease: 21, dependencies: ['lib/main', 'app/main'],
            classPath: ['/m2/guava.jar', '/m2/junit.jar'],
        },
        {
            // no output of its own: the project's; no --release: 0, not the compliance level
            name: 'lib/main', sourceDirectories: ['/w/lib/src/main/java'], outputPath: '/w/lib/target/classes',
            test: false, sourceRelease: 0, dependencies: [], classPath: ['/m2/guava.jar'],
        },
    ]);
});

test('a project output folder is a set\'s identity, never another set\'s class-path entry', async () => {
    const { execute } = twoProjects();
    const config = await buildConfig(execute, OPTIONS);
    const outputs = new Set(config.moduleSourceSets!.map((s) => s.outputPath));
    for (const set of config.moduleSourceSets!) {
        assert.ok(set.classPath.every((p) => !outputs.has(p)), `${set.name} lists an output folder`);
    }
});

test('a library both scopes use is main; a dependency on a project without sources is dropped', async () => {
    const { execute } = twoProjects({
        [`getSettings:${LIB}`]: {
            [CLASSPATH_ENTRIES]: [
                { kind: 3, path: '/w/lib/src/main/java', attributes: {} },
                { kind: 1, path: '/m2/shared.jar', attributes: TEST },
                { kind: 1, path: '/m2/shared.jar', attributes: {} },
                { kind: 2, path: '/parent-pom', attributes: {} },
            ],
            [OUTPUT_PATH]: '/w/lib/target/classes',
        },
    });
    const lib = (await buildConfig(execute, OPTIONS)).moduleSourceSets!.find((s) => s.name === 'lib/main')!;
    assert.deepStrictEqual(lib.classPath, ['/m2/shared.jar']);
    assert.deepStrictEqual(lib.dependencies, [], 'a set the daemon does not get must not be depended on');
});

test('a source root no set covers falls back to the flat form rather than being skipped', async () => {
    // jdt.ls leaves out a source folder that is the project's root; listSourcePaths still reports it
    const { execute } = twoProjects({
        'java.project.listSourcePaths': {
            data: [
                { path: '/w/app/src/main/java', displayPath: 'app/src/main/java', projectName: 'app' },
                { path: '/w/scripts', displayPath: 'scripts', projectName: 'scripts' },
            ],
        },
        'getClasspaths:runtime': { projectRoot: APP, classpaths: ['/w/app/target/classes'], modulepaths: [] },
        'getClasspaths:test': { projectRoot: APP, classpaths: [], modulepaths: [] },
    });
    const config = await buildConfig(execute, OPTIONS);
    assert.strictEqual(config.moduleSourceSets, undefined);
    assert.strictEqual(config.sources.length, 2);
});

test('the release is an API release only with --release on', () => {
    assert.strictEqual(apiRelease('enabled', '21'), 21);
    assert.strictEqual(apiRelease('enabled', '1.8'), 8);
    assert.strictEqual(apiRelease('disabled', '21'), 0);
    assert.strictEqual(apiRelease(undefined, undefined), 0);
});

test('test-root detection covers the common layouts', () => {
    assert.ok(looksLikeTestRoot('/w/app/src/test/java'));
    assert.ok(looksLikeTestRoot('/w/app/test'));
    assert.ok(looksLikeTestRoot('/w/app/src/integrationTest/java'));
    assert.ok(!looksLikeTestRoot('/w/app/src/main/java'));
    // a project that merely lives under a directory called "latest" is not a test root
    assert.ok(!looksLikeTestRoot('/w/latest/src/main/java'));
});
