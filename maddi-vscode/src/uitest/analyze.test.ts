/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as assert from 'assert';
import * as childProcess from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import type { MaddiApi, RunOutcome } from '../extension';

/*
 * The UI tests: this file runs inside a real VS Code (see .vscode-test.mjs), with redhat.java and this extension
 * loaded, on a copy of uitest-fixture/Demo. It drives the editor through its API and reads back what a user would
 * see: the inline hints, the diagnostics in the Problems panel.
 *
 * It needs a JDK 25 or later for the daemon (MADDI_JDK_HOME, else `java_home -v 25+` on macOS, else JAVA_HOME), and
 * a daemon distribution (MADDI_DAEMON_INSTALL, else the bundled daemon/, else maddi-ide-daemon's installDist).
 */

const EXTENSION_ID = 'codelaser.maddi-vscode';

describe('maddi in VS Code', function () {

    let api: MaddiApi;

    before(async function () {
        const settings = vscode.workspace.getConfiguration('maddi');
        await settings.update('jdkHome', jdkHome(), vscode.ConfigurationTarget.Global);
        const daemon = daemonInstall();
        if (daemon) await settings.update('daemonInstall', daemon, vscode.ConfigurationTarget.Global);
        await settings.update('daemonXmxMb', 2048, vscode.ConfigurationTarget.Global);
        // every computed annotation, so that the assertions do not depend on what the default filter hides
        await settings.update('hints', 'all', vscode.ConfigurationTarget.Global);
        await settings.update('warnNearMisses', true, vscode.ConfigurationTarget.Global);
        await settings.update('incrementalReanalysis', false, vscode.ConfigurationTarget.Global);

        const extension = vscode.extensions.getExtension<MaddiApi>(EXTENSION_ID);
        assert.ok(extension, `${EXTENSION_ID} is not loaded`);
        api = await extension.activate();

        await open('Point.java');
        // resolves when the run has ended: finished, stopped or failed
        await vscode.commands.executeCommand('maddi.analyze');
        await analyzeAndCheck(api);
    });

    it('shows an immutable type as such', async function () {
        const labels = (await maddiHints('Point.java')).map((h) => h.label);
        log('Point.java', labels);
        assert.ok(labels.some((l) => l.includes('@Immutable')), `no @Immutable hint in ${JSON.stringify(labels)}`);
    });

    it('shows a modifying method', async function () {
        const hints = await maddiHints('Counter.java');
        log('Counter.java', hints.map((h) => h.label));
        const increment = await lineOf('Counter.java', 'public void increment()');
        const onIncrement = hints.filter((h) => h.line === increment).map((h) => h.label);
        assert.ok(onIncrement.some((l) => l.includes('@Modified')),
            `no @Modified on increment() (line ${increment + 1}): ${JSON.stringify(onIncrement)}`);
    });

    it('reports the near miss in the Problems panel', async function () {
        const diagnostics = vscode.languages.getDiagnostics(uriOf('Names.java'))
            .filter((d) => d.source === undefined || d.source === 'maddi');
        log('Names.java diagnostics', diagnostics.map((d) => `${d.range.start.line + 1}: ${d.message}`));
        assert.ok(diagnostics.some((d) => /container/i.test(d.message)),
            `no near-miss diagnostic: ${JSON.stringify(diagnostics.map((d) => d.message))}`);
    });

    it('moves the hints with an edit above them', async function () {
        const before = await maddiHints('Counter.java');
        assert.ok(before.length > 0, 'no hints to move');

        const editor = await open('Counter.java');
        const edit = new vscode.WorkspaceEdit();
        edit.insert(editor.document.uri, new vscode.Position(0, 0), '// one\n// two\n');
        assert.ok(await vscode.workspace.applyEdit(edit), 'the edit was not applied');

        const after = await maddiHints('Counter.java');
        try {
            assert.deepStrictEqual(after.map((h) => `${h.line - 2}:${h.label}`).sort(),
                before.map((h) => `${h.line}:${h.label}`).sort());
        } finally {
            await vscode.commands.executeCommand('workbench.action.files.revert');
        }
    });
});

describe('incremental re-analysis', function () {

    let api: MaddiApi;
    /** The hints of the three files after the first, cold run of the warm daemon. */
    let first: Map<string, string[]>;
    /** And after the edit of Box, re-analysed incrementally; then after the second edit. */
    let incremental: Map<string, string[]>;
    let incrementalRun: RunOutcome | undefined;

    const FILES = ['Cell.java', 'Box.java', 'Shelf.java'];

    before(async function () {
        const extension = vscode.extensions.getExtension<MaddiApi>(EXTENSION_ID);
        assert.ok(extension);
        api = await extension.activate();
        // a change of this setting restarts the daemon, warm: its first run is cold, the next one incremental
        await vscode.workspace.getConfiguration('maddi')
            .update('incrementalReanalysis', true, vscode.ConfigurationTarget.Global);
        const cold = await analyzeAndCheck(api);
        assert.notStrictEqual(cold.resultOutcome, 'INCREMENTAL', 'the first run of a warm daemon is cold');
        first = await hintsOf(FILES);

        // the changed type: peek now modifies its cell. Analyze saves the editor, and jdt.ls compiles it.
        await replaceInFile('Box.java', 'return cell.get();', 'cell.set(cell.get() + 1); return cell.get();');
        incrementalRun = await analyzeAndCheck(api);
        incremental = await hintsOf(FILES);
    });

    after(async function () {
        await vscode.workspace.getConfiguration('maddi')
            .update('incrementalReanalysis', false, vscode.ConfigurationTarget.Global);
    });

    it('re-analyses incrementally, re-parsing only the changed type', function () {
        // Box's change is visible to Shelf, which calls peek: the early cutoff sees that, and analyses the whole order
        // again; what this test pins is that only Box was parsed again
        assert.strictEqual(incrementalRun?.resultOutcome, 'INCREMENTAL', JSON.stringify(incrementalRun));
        const steps = incrementalRun?.steps ?? [];
        assert.ok(steps.includes('parse: re-parsing 1 changed type(s)'), `steps: ${JSON.stringify(steps)}`);
    });

    it('shows the edit on the changed type', async function () {
        // the edit is below the parameter's line, so its position is the same before and after
        assert.match(await snapshotAt(first, 'Box.java', 'public int peek(', 'Cell cell'), /@NotModified/,
            'before the edit');
        const after = await parameterHint('Box.java', 'public int peek(', 'Cell cell');
        assert.match(after, /@Modified/);
        assert.doesNotMatch(after, /@NotModified/);
    });

    it('leaves the type before it as it was', function () {
        assert.deepStrictEqual(incremental.get('Cell.java'), first.get('Cell.java'));
    });

    it('carries the change into the rewired type that uses both', async function () {
        assert.match(await parameterHint('Shelf.java', 'public int read(', 'Cell cell'), /@Modified/,
            'read hands its cell to peek, which now modifies it');
        assert.doesNotMatch(await parameterHint('Shelf.java', 'public int read(', 'Cell cell'), /@NotModified/);
        assert.match(await parameterHint('Shelf.java', 'public int direct(', 'Cell cell'), /@NotModified/,
            'direct does not call Box, and stays as it was');
    });

    it('keeps the rewired type as it was when the edit is invisible to it', async function () {
        // the same summary for peek, through a local alias of its parameter: it still modifies its cell, and returns
        // what the cell holds, so Shelf sees nothing new and the cutoff holds. (Returning a local int instead,
        // `int next = ...; return next;`, is a different summary: the return value no longer comes from the cell.)
        await replaceInFile('Box.java', 'cell.set(cell.get() + 1); return cell.get();',
            'Cell c = cell; c.set(c.get() + 1); return c.get();');
        const run = await analyzeAndCheck(api);
        assert.strictEqual(run.resultOutcome, 'INCREMENTAL');
        const steps = run.steps ?? [];
        assert.ok(steps.includes('parse: re-parsing 1 changed type(s)'), `steps: ${JSON.stringify(steps)}`);
        assert.ok(steps.includes('analyze: modification analysis, seeded with 1 type(s)'),
            `steps: ${JSON.stringify(steps)}`);
        // no pass over the whole order: that would be the cutoff falling back to a cold analysis
        assert.ok(!steps.some((step) => step.includes('(first)')), `the cutoff did not hold: ${JSON.stringify(steps)}`);

        const now = await hintsOf(FILES);
        assert.deepStrictEqual(now.get('Shelf.java'), incremental.get('Shelf.java'));
        assert.deepStrictEqual(now.get('Cell.java'), incremental.get('Cell.java'));
        assert.match(await parameterHint('Box.java', 'public int peek(', 'Cell cell'), /@Modified/);
        incremental = now;
    });

    it('says what a cold run says', async function () {
        await vscode.workspace.getConfiguration('maddi')
            .update('incrementalReanalysis', false, vscode.ConfigurationTarget.Global);
        const cold = await analyzeAndCheck(api);
        assert.notStrictEqual(cold.resultOutcome, 'INCREMENTAL');
        assert.deepStrictEqual(await hintsOf(FILES), incremental);
    });
});

/** Run Analyze with maddi; it resolves when the run has ended. Fails unless it finished. */
async function analyzeAndCheck(api: MaddiApi): Promise<RunOutcome> {
    await vscode.commands.executeCommand('maddi.analyze');
    const run = api.lastRun();
    console.log(`    the run: ${JSON.stringify(run)}`);
    assert.ok(run, 'no run');
    assert.strictEqual(run.outcome, 'finished', run.message);
    return run;
}

async function hintsOf(files: string[]): Promise<Map<string, string[]>> {
    const map = new Map<string, string[]>();
    for (const file of files) {
        map.set(file, (await maddiHints(file)).map((h) => `${h.line + 1}:${h.character + 1} ${h.label}`).sort());
    }
    return map;
}

/** In a snapshot of {@link hintsOf}: the hint at the start of `parameter`, on the line that contains `lineText`. */
async function snapshotAt(snapshot: Map<string, string[]>, file: string, lineText: string,
                          parameter: string): Promise<string> {
    const line = await lineOf(file, lineText);
    const column = (await open(file)).document.lineAt(line).text.indexOf(parameter);
    const prefix = `${line + 1}:${column + 1} `;
    const found = (snapshot.get(file) ?? []).filter((h) => h.startsWith(prefix));
    assert.strictEqual(found.length, 1, `at ${file}:${prefix}in ${JSON.stringify(snapshot.get(file))}`);
    return found[0].substring(prefix.length);
}

/** The hint on a parameter: on the line that contains `lineText`, at the start of `parameter`. */
async function parameterHint(file: string, lineText: string, parameter: string): Promise<string> {
    const line = await lineOf(file, lineText);
    const document = (await open(file)).document;
    const column = document.lineAt(line).text.indexOf(parameter);
    assert.ok(column >= 0, `'${parameter}' is not on line ${line + 1} of ${file}`);
    const hints = (await maddiHints(file)).filter((h) => h.line === line && h.character === column);
    assert.strictEqual(hints.length, 1, `hints at ${file}:${line + 1}:${column + 1}: ${JSON.stringify(
        (await maddiHints(file)).filter((h) => h.line === line))}`);
    return hints[0].label;
}

async function replaceInFile(file: string, from: string, to: string): Promise<void> {
    const document = (await open(file)).document;
    const offset = document.getText().indexOf(from);
    assert.ok(offset >= 0, `'${from}' is not in ${file}`);
    const edit = new vscode.WorkspaceEdit();
    edit.replace(document.uri, new vscode.Range(document.positionAt(offset), document.positionAt(offset + from.length)),
        to);
    assert.ok(await vscode.workspace.applyEdit(edit), 'the edit was not applied');
}

interface Hint {
    line: number;
    character: number;
    label: string;
}

/** The inline hints of maddi in a file: those of redhat.java are switched off in the fixture's settings. */
async function maddiHints(file: string): Promise<Hint[]> {
    const editor = await open(file);
    const document = editor.document;
    const range = new vscode.Range(0, 0, document.lineCount, 0);
    const hints = await vscode.commands.executeCommand<vscode.InlayHint[]>(
        'vscode.executeInlayHintProvider', document.uri, range) ?? [];
    return hints
        .map((h) => ({
            line: h.position.line,
            character: h.position.character,
            label: typeof h.label === 'string' ? h.label : h.label.map((part) => part.value).join(''),
        }))
        .filter((h) => h.label.includes('@'));
}

async function lineOf(file: string, text: string): Promise<number> {
    const document = (await open(file)).document;
    for (let i = 0; i < document.lineCount; i++) {
        if (document.lineAt(i).text.includes(text)) return i;
    }
    throw new Error(`'${text}' is not in ${file}`);
}

async function open(file: string): Promise<vscode.TextEditor> {
    const document = await vscode.workspace.openTextDocument(uriOf(file));
    return vscode.window.showTextDocument(document);
}

function uriOf(file: string): vscode.Uri {
    const folder = vscode.workspace.workspaceFolders?.[0];
    assert.ok(folder, 'no workspace folder open');
    return vscode.Uri.joinPath(folder.uri, 'src', 'demo', file);
}

function log(what: string, values: string[]): void {
    console.log(`    ${what}: ${values.join(' | ')}`);
}

function jdkHome(): string {
    const fromEnv = process.env.MADDI_JDK_HOME;
    if (fromEnv) return fromEnv;
    if (process.platform === 'darwin') {
        try {
            return childProcess.execFileSync('/usr/libexec/java_home', ['-v', '25+'], { encoding: 'utf8' }).trim();
        } catch {
            // no JDK 25 known to java_home: try JAVA_HOME
        }
    }
    const javaHome = process.env.JAVA_HOME;
    assert.ok(javaHome, 'set MADDI_JDK_HOME to a JDK 25 or later');
    return javaHome;
}

/** Undefined means the daemon bundled in the extension's daemon/ directory. */
function daemonInstall(): string | undefined {
    const fromEnv = process.env.MADDI_DAEMON_INSTALL;
    if (fromEnv) return fromEnv;
    // out/uitest/ -> the extension's root
    const root = path.resolve(__dirname, '..', '..');
    if (fs.existsSync(path.join(root, 'daemon', 'bin'))) return undefined;
    const built = path.resolve(root, '..', 'maddi-ide-daemon', 'build', 'install', 'maddi-ide-daemon');
    assert.ok(fs.existsSync(built),
        'no daemon: run `task vscode:daemon`, or set MADDI_DAEMON_INSTALL to a daemon distribution');
    return built;
}
