/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import { ChildProcess, spawn, spawnSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Starts the analysis daemon and finds out which port it chose.
 *
 * maddi needs a JDK 25+, VS Code does not ship one, and the analyzer must not run inside the extension host
 * anyway — so it runs as a separate process, exactly as it does for IntelliJ and Eclipse, and is spoken to
 * over a socket. The daemon prints `DAEMON_PORT=<n>` on stdout once it is listening; asking for port 0 and
 * reading it back avoids picking a port that turns out to be taken.
 */
export interface DaemonHandle {
    port: number;
    process: ChildProcess;
}

export interface LaunchOptions {
    /** The daemon distribution: the directory containing `bin/` and `lib/`. */
    installDir: string;
    /** Home of a JDK 25+; becomes JAVA_HOME for the daemon process. */
    jdkHome: string;
    maxHeapMb: number;
    /** Experimental: the daemon keeps the last run's state and re-analyses only what an edit changed. */
    incremental?: boolean;
    timeoutMs?: number;
    /** Where the daemon's own output goes, so a failure to start is diagnosable. */
    log?: (line: string) => void;
}

/** The distribution's launch description, `bin/maddi-ide-daemon.launch`: main class, JVM arguments, jars of `lib/`. */
export interface LaunchFile {
    mainClass: string;
    jvmArgs: string[];
    jars: string[];
}

export function launchFilePath(installDir: string): string {
    return path.join(installDir, 'bin', 'maddi-ide-daemon.launch');
}

export function parseLaunchFile(text: string, where: string): LaunchFile {
    let mainClass: string | undefined;
    const jvmArgs: string[] = [];
    const jars: string[] = [];
    for (const raw of text.split(/\r?\n/)) {
        if (raw.trim() === '' || raw.startsWith('#')) continue;
        const eq = raw.indexOf('=');
        if (eq < 0) throw new Error(`malformed line in ${where}: ${raw}`);
        const key = raw.substring(0, eq);
        const value = raw.substring(eq + 1);
        if (key === 'main') mainClass = value;
        else if (key === 'jvm') jvmArgs.push(value);
        else if (key === 'lib') jars.push(value);
        else throw new Error(`unknown key '${key}' in ${where}`);
    }
    if (mainClass === undefined || jars.length === 0) throw new Error(`incomplete launch description: ${where}`);
    return { mainClass, jvmArgs, jars };
}

/**
 * The command that starts the daemon, run in the install directory: `<jdk>/bin/java`, the JVM arguments, a class
 * path of `lib/<jar>` relative to the install directory, the main class, `--port 0`.
 *
 * Not the start scripts in `bin/` (maddi-dist#9): Node does not spawn a `.bat` without a shell, and the `.bat` spells
 * out every jar with its absolute path in one line, which cmd.exe refuses past 8191 characters. The relative class
 * path stays about 2,400 characters whatever the install path, and holds no character outside ASCII.
 */
export function daemonCommand(launch: LaunchFile, jdkHome: string, extraJvmArgs: string[],
                              windows: boolean): { java: string; args: string[] } {
    const p = windows ? path.win32 : path.posix;
    const java = p.join(jdkHome, 'bin', windows ? 'java.exe' : 'java');
    const classPath = launch.jars.map((jar) => p.join('lib', jar)).join(windows ? ';' : ':');
    return {
        java,
        args: [...launch.jvmArgs, ...extraJvmArgs, '-cp', classPath, launch.mainClass, '--port', '0'],
    };
}

export async function launchDaemon(options: LaunchOptions): Promise<DaemonHandle> {
    const launchFile = launchFilePath(options.installDir);
    if (!fs.existsSync(launchFile)) {
        throw new Error(`no daemon launch description at ${launchFile}; set maddi.daemonInstall`);
    }
    const launch = parseLaunchFile(fs.readFileSync(launchFile, 'utf8'), launchFile);
    const extraJvmArgs = [`-Xmx${options.maxHeapMb}m`, '-XX:+UseG1GC'];
    if (options.incremental) extraJvmArgs.push('-Dmaddi.daemon.warm=true');
    const { java, args } = daemonCommand(launch, options.jdkHome, extraJvmArgs, process.platform === 'win32');

    const child = spawn(java, args, {
        env: {
            ...process.env,
            // the daemon itself may run builds, which look for JAVA_HOME
            JAVA_HOME: options.jdkHome,
            // the daemon exits when this process (the extension host) does, also when it never gets to stop it
            MADDI_PARENT_PID: String(process.pid),
        },
        // the class path is relative to it
        cwd: options.installDir,
        windowsHide: true,
    });

    const timeoutMs = options.timeoutMs ?? 60_000;
    const port = await new Promise<number>((resolve, reject) => {
        let settled = false;
        let stdout = '';
        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            child.kill();
            reject(new Error(`daemon did not report a port within ${timeoutMs} ms. Output so far:\n${stdout}`));
        }, timeoutMs);

        const finish = (fn: () => void) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            fn();
        };

        child.stdout?.setEncoding('utf8');
        child.stdout?.on('data', (chunk: string) => {
            stdout += chunk;
            options.log?.(chunk.trimEnd());
            const match = /DAEMON_PORT=(\d+)/.exec(stdout);
            if (match) finish(() => resolve(Number(match[1])));
        });
        child.stderr?.setEncoding('utf8');
        child.stderr?.on('data', (chunk: string) => options.log?.(chunk.trimEnd()));
        child.on('error', (e) => finish(() => reject(e)));
        child.on('exit', (code) =>
            finish(() => reject(new Error(`daemon exited with code ${code} before reporting a port:\n${stdout}`))));
    });

    return { port, process: child };
}

/**
 * Kill the daemon now, whatever it is doing. The child is the JVM itself; on Windows the tree is killed as well, for
 * whatever the daemon started.
 */
export function killDaemon(handle: DaemonHandle): void {
    const pid = handle.process.pid;
    if (process.platform === 'win32' && pid !== undefined) {
        spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true });
    }
    handle.process.kill('SIGKILL');
}
