/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { PARTIAL_RESULT, PartialResult, Result, merge } from './analysisModel';
import { buildConfig } from './configBuilder';
import { DaemonClient, Frame, PROTOCOL_VERSION } from './daemonClient';
import { DaemonHandle, killDaemon, launchDaemon, launcherPath } from './daemonLauncher';
import { toDiagnostics } from './diagnostics';
import { MaddiInlayHintsProvider } from './hints';
import { CompileStatus, compileWorkspace, waitForStandardServer } from './javaLanguageServer';
import { ResultStore } from './results';

/**
 * The maddi VS Code front-end.
 *
 * Same architecture as the IntelliJ and Eclipse plugins: the analyzer is far too heavy — and needs a much
 * newer JDK — to live inside the editor, so it runs as a daemon and is spoken to over a socket in plain
 * JSON. What is different here is where the project model comes from: VS Code has none, so it is asked of
 * jdt.ls (see {@link buildConfig}), which also supplies the compiled `.class` files the parser needs.
 */

let daemon: DaemonHandle | undefined;
/** Whether the running daemon was started incremental: a change of the setting relaunches it. */
let daemonIncremental = false;
let diagnostics: vscode.DiagnosticCollection;
let output: vscode.OutputChannel;
let store: ResultStore;
let running = false;
/** Set when the user stops the run in progress: its failure is then the stop, not an error. */
let stopRequested = false;
/** Set once the run in progress has saved the editors: from then on, it does not see what is typed. */
let runHasSaved = false;
/** Analyze was asked for again during a run that will not see the latest edits: one more run when it ends. */
let rerunRequested = false;

export function activate(context: vscode.ExtensionContext): void {
    diagnostics = vscode.languages.createDiagnosticCollection('maddi');
    output = vscode.window.createOutputChannel('maddi');
    store = new ResultStore();
    const hints = new MaddiInlayHintsProvider(store);

    context.subscriptions.push(
        diagnostics,
        output,
        store,
        hints,
        vscode.languages.registerInlayHintsProvider({ language: 'java' }, hints),
        // the hints follow their declarations through edits (see edits.ts)
        vscode.workspace.onDidChangeTextDocument((event) => {
            if (!isProjectJava(event.document)) return;
            store.edit(event.document.uri, event.contentChanges.map((c) => ({
                startLine: c.range.start.line, startCol: c.range.start.character,
                endLine: c.range.end.line, endCol: c.range.end.character, text: c.text,
            })));
        }),
        vscode.workspace.onDidSaveTextDocument((document) => {
            if (isProjectJava(document)) store.saved(document.uri);
        }),
        vscode.workspace.onDidCloseTextDocument((document) => {
            if (isProjectJava(document)) store.closed(document.uri);
        }),
        vscode.commands.registerCommand('maddi.analyze', () => analyzeCommand(context)),
        vscode.commands.registerCommand('maddi.stop', () => stopCommand()),
        // the daemon is a child process: it does not go away with the window unless it is killed
        { dispose: () => stopDaemon() });
}

export function deactivate(): void {
    stopDaemon();
}

/** A Java source on disk: what the analysis reads, as opposed to a library's `jdt://` class file. */
function isProjectJava(document: vscode.TextDocument): boolean {
    return document.languageId === 'java' && document.uri.scheme === 'file';
}

function stopDaemon(): void {
    if (daemon) killDaemon(daemon);
    daemon = undefined;
    void vscode.commands.executeCommand('setContext', 'maddi.running', false);
}

/**
 * Stop the run in progress by killing the daemon, as the IntelliJ and Eclipse plugins do: it is the one way out of
 * a fixpoint iteration in progress. The waiting analysis then fails with "daemon closed the connection", what the
 * run streamed stays on screen, and the next analysis starts a fresh daemon. Nothing is lost that a new run does
 * not rebuild, since every run builds its own model.
 */
function stopCommand(): void {
    if (!running) {
        vscode.window.showInformationMessage('maddi is not analyzing.');
        return;
    }
    stopRequested = true;
    rerunRequested = false; // stopping empties the queue, as in the other front-ends
    output.appendLine('stopping the analysis');
    stopDaemon();
}

async function analyzeCommand(context: vscode.ExtensionContext): Promise<void> {
    if (running) {
        // Before the run has saved the editors, it will read what is there now: the request is part of it. After,
        // it asks for one more run when this one ends, rather than being dropped (as the other front-ends do).
        if (runHasSaved && !rerunRequested) {
            rerunRequested = true;
            vscode.window.showInformationMessage('maddi will analyze again when the current run ends.');
        }
        return;
    }
    const folder = vscode.workspace.workspaceFolders?.[0];
    if (!folder) {
        vscode.window.showWarningMessage('maddi: open a folder containing a Java project first.');
        return;
    }
    const settings = vscode.workspace.getConfiguration('maddi');
    const jdkHome = settings.get<string>('jdkHome')?.trim();
    if (!jdkHome) {
        const choice = await vscode.window.showWarningMessage(
            'maddi needs a JDK 25 or later to run the analysis on.', 'Open settings');
        if (choice) await vscode.commands.executeCommand('workbench.action.openSettings', 'maddi.jdkHome');
        return;
    }

    running = true;
    stopRequested = false;
    runHasSaved = false;
    let again = false;
    void vscode.commands.executeCommand('setContext', 'maddi.running', true);
    try {
        await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification, title: 'maddi', cancellable: true },
            (progress, token) => {
                token.onCancellationRequested(() => stopCommand());
                return analyze(context, folder, jdkHome, settings, progress);
            });
    } catch (e) {
        if (stopRequested) {
            // What the run streamed stays: it is established, only possibly weaker than a finished run's, and the
            // hover says so (provisional). Findings come only at the end of a run, so the previous run's stay too.
            output.appendLine('analysis stopped');
            vscode.window.showInformationMessage('maddi: analysis stopped. What it had established stays on screen.');
            return;
        }
        // Drop what is on screen: it came from a run that did not finish, and stale findings presented as
        // current are worse than none — they look like an answer.
        diagnostics.clear();
        store.clear();
        const message = e instanceof Error ? e.message : String(e);
        output.appendLine(`analysis failed: ${message}`);
        vscode.window.showErrorMessage(`maddi: ${message}`, 'Show log')
            .then((choice) => { if (choice) output.show(); });
    } finally {
        again = rerunRequested && !stopRequested;
        running = false;
        stopRequested = false;
        runHasSaved = false;
        rerunRequested = false;
        void vscode.commands.executeCommand('setContext', 'maddi.running', false);
    }
    if (again) void analyzeCommand(context);
}

async function analyze(
    context: vscode.ExtensionContext,
    folder: vscode.WorkspaceFolder,
    jdkHome: string,
    settings: vscode.WorkspaceConfiguration,
    progress: vscode.Progress<{ message?: string }>): Promise<void> {

    progress.report({ message: 'waiting for the Java language server' });
    await waitForStandardServer();

    // The daemon reads the files on disk, and jdt.ls compiles them: an unsaved editor would be analysed as it was
    // last saved, so a class still being typed showed nothing. As the other front-ends do since 0.9.6, save first.
    progress.report({ message: 'saving' });
    await saveJavaEditors();
    runHasSaved = true;
    // from here on, what the run reports is placed on the sources as saved now; edits made during the run are
    // followed on top of it (see ResultStore.beginRun)
    store.beginRun();

    // The parser reads .class files, so compile before asking for anything. jdt.ls autobuilds, but a file
    // saved a moment ago may still be in flight; this resolves only once the server's build has returned.
    progress.report({ message: 'compiling' });
    const status = await compileWorkspace(false);
    if (status === CompileStatus.failed || status === CompileStatus.cancelled) {
        throw new Error(`the Java build did not complete (${CompileStatus[status]}); fix the build first`);
    }
    if (status === CompileStatus.withError) {
        // partial output still analyses: maddi tolerates a project that does not fully compile
        output.appendLine('the Java build reported errors; analyzing what compiled');
    }

    progress.report({ message: 'starting the analysis daemon' });
    const handle = await ensureDaemon(context, jdkHome, settings);

    progress.report({ message: 'building the project model' });
    const config = await buildConfig(
        (command, ...args) => vscode.commands.executeCommand(command, ...args) as Promise<unknown>,
        {
            jdkHome,
            warnNearMisses: settings.get<boolean>('warnNearMisses') ?? false,
            workingDirectory: folder.uri.fsPath,
        });
    const sets = config.moduleSourceSets ?? [];
    output.appendLine(sets.length > 0
        ? `analyzing ${sets.length} module source set(s): ${sets.map((s) => s.name).join(', ')}`
        // the flat form: jdt.ls could not describe the workspace as source sets (see moduleSourceSets)
        : `analyzing ${config.sources.length} source root(s), ${config.classpath.length} classpath entr(ies)`);

    const client = new DaemonClient(handle.port);
    try {
        await client.connect();
        const ack = await client.handshake();
        // both sides check the protocol version: a daemon from 0.9.6 refuses another with an error, an older one
        // acknowledges anything with its own version 1
        if (ack.type === 'error') {
            throw new Error(`the maddi daemon refused this extension: ${ack.message ?? JSON.stringify(ack)}`);
        }
        if (ack.protocolVersion !== PROTOCOL_VERSION) {
            throw new Error(`the maddi daemon (maddi ${ack.maddiVersion ?? 'unknown'}) speaks protocol `
                + `${ack.protocolVersion}, this extension speaks ${PROTOCOL_VERSION}: use a daemon of the same release`);
        }
        // WHICH daemon answered: the bundled one is a build, not a version, and a stale bundle presents as an
        // analyzer regression rather than as a stale bundle (2026-08-24). See DaemonMain's build stamp.
        output.appendLine(`daemon build ${ack.buildStamp ?? 'unknown'}`);

        // Values established by each analysis pass arrive before the run ends, so findings and hints appear
        // progressively instead of the editor staying blank until the last pass.
        let displayed: Result | undefined;
        const frame = await client.analyze(`req-${Date.now()}`, config, (streamed: Frame) => {
            if (streamed.type === PARTIAL_RESULT) {
                const partial = streamed as unknown as PartialResult;
                displayed = merge(displayed, partial);
                // show them as they arrive: on a large project this is the difference between an annotated
                // file in seconds and a blank one until the last pass
                store.setPartial(displayed);
                // what has been decided, not a percentage: the run is a fixpoint iteration whose length is
                // not known in advance, so a fraction would be invented
                progress.report({
                    message: `pass ${partial.iteration}, ${displayed.elementAnnotations.length} element(s) so far`,
                });
            } else {
                // the heartbeat carries a message during the long analysis phase; it is what shows the run
                // is alive rather than hung
                const phase = String(streamed.phase ?? 'analyzing');
                const message = String(streamed.message ?? '');
                progress.report({ message: message.length > 0 ? `${phase}: ${message}` : phase });
            }
        });

        if (frame.type === 'error') {
            throw new Error(String(frame.message ?? 'the daemon reported an error'));
        }
        const result = frame as unknown as Result;
        applyResult(result);
        output.appendLine(
            `done in ${result.elapsedMillis} ms: ${result.findings.length} finding(s), ` +
            `${result.elementAnnotations.length} annotated element(s), ${result.hintsLoaded} hints loaded`);
    } finally {
        client.dispose();
    }
}

async function saveJavaEditors(): Promise<void> {
    const dirty = vscode.workspace.textDocuments.filter((d) => d.isDirty && isProjectJava(d));
    const saved = await Promise.all(dirty.map((d) => d.save()));
    const failed = dirty.filter((_, i) => !saved[i]).map((d) => path.basename(d.uri.fsPath));
    if (failed.length > 0) output.appendLine(`could not save ${failed.join(', ')}; analyzing them as on disk`);
}

function applyResult(result: Result): void {
    store.set(result);
    diagnostics.clear();
    for (const [uri, list] of toDiagnostics(result.findings)) {
        diagnostics.set(vscode.Uri.parse(uri), list);
    }
}

async function ensureDaemon(
    context: vscode.ExtensionContext,
    jdkHome: string,
    settings: vscode.WorkspaceConfiguration): Promise<DaemonHandle> {
    const incremental = settings.get<boolean>('incrementalReanalysis') ?? false;
    if (daemon && daemon.process.exitCode === null && incremental === daemonIncremental) return daemon;
    stopDaemon();

    const configured = settings.get<string>('daemonInstall')?.trim();
    const installDir = configured && configured.length > 0
        ? configured
        : path.join(context.extensionPath, 'daemon');
    // Say which setting to change: an override that holds no daemon is a stale setting, not a broken install, and
    // the bare launcher path left the user to work that out (the IntelliJ plugin says the same since 0.9.6).
    if (!fs.existsSync(launcherPath(installDir))) {
        throw new Error(configured
            ? `maddi.daemonInstall is set to ${installDir}, which holds no maddi daemon (no ${path.relative(installDir,
                launcherPath(installDir))}). Clear the setting to use the daemon bundled with the extension, or point `
              + 'it at a daemon distribution (the directory containing bin/ and lib/).'
            : `the daemon bundled with the extension is missing from ${installDir}. Reinstall the extension, or set `
              + 'maddi.daemonInstall to a daemon distribution.');
    }
    output.appendLine(`starting the daemon from ${installDir} on ${jdkHome}`);
    daemon = await launchDaemon({
        installDir,
        jdkHome,
        maxHeapMb: settings.get<number>('daemonXmxMb') ?? 4096,
        incremental,
        log: (line) => output.appendLine(line),
    });
    daemonIncremental = incremental;
    output.appendLine(`daemon listening on port ${daemon.port}`);
    return daemon;
}
