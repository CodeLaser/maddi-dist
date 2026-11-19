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
import type { MaddiApi } from '../extension';

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

        const extension = vscode.extensions.getExtension<MaddiApi>(EXTENSION_ID);
        assert.ok(extension, `${EXTENSION_ID} is not loaded`);
        api = await extension.activate();

        await open('Point.java');
        // resolves when the run has ended: finished, stopped or failed
        await vscode.commands.executeCommand('maddi.analyze');
        const run = api.lastRun();
        console.log(`    the run: ${JSON.stringify(run)}`);
        assert.strictEqual(run?.outcome, 'finished', run?.message);
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

interface Hint {
    line: number;
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
