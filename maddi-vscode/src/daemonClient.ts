/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as net from 'net';
import { AnalyzeConfig } from './analysisModel';

/**
 * The daemon transport: NDJSON (one UTF-8 JSON object per line) over a loopback TCP socket, discriminated on
 * `type`. The Java front-ends share `maddi-ide-client`; VS Code cannot, so this reimplements the same
 * protocol — deliberately small, which is the point of the protocol being plain JSON.
 */
/** The daemon protocol this client speaks; `DaemonProtocol.PROTOCOL_VERSION` (Java) documents the versions. */
export const PROTOCOL_VERSION = 2;

export type Frame = Record<string, any>;

/**
 * Splits an incoming byte stream into NDJSON frames.
 *
 * Written as its own class so it can be tested without a socket. A naive implementation would assume one
 * `data` event equals one message, which holds right up until a result large enough to be split across TCP
 * segments arrives — i.e. on exactly the big projects this feature exists for.
 */
export class FrameReader {
    private buffer = '';

    /** Feed a chunk; returns the complete frames it produced (possibly none). */
    push(chunk: string): Frame[] {
        this.buffer += chunk;
        const frames: Frame[] = [];
        let newline: number;
        while ((newline = this.buffer.indexOf('\n')) >= 0) {
            const line = this.buffer.slice(0, newline).trim();
            this.buffer = this.buffer.slice(newline + 1);
            if (line.length === 0) continue;
            frames.push(JSON.parse(line));
        }
        return frames;
    }
}

export class DaemonClient {
    private socket: net.Socket | undefined;
    private readonly reader = new FrameReader();
    /** Frames that arrived before anyone asked for them; drained by the next read. */
    private pending: Frame[] = [];
    private waiter: { resolve: (frame: Frame) => void; reject: (error: Error) => void } | undefined;
    private failure: Error | undefined;

    constructor(private readonly port: number, private readonly timeoutMs = 600_000) {}

    async connect(): Promise<void> {
        await new Promise<void>((resolve, reject) => {
            const socket = net.createConnection({ port: this.port, host: '127.0.0.1' }, resolve);
            socket.setEncoding('utf8');
            socket.setTimeout(this.timeoutMs);
            socket.on('data', (chunk: string) => this.onData(chunk));
            socket.on('error', (e) => this.fail(e));
            socket.on('timeout', () => this.fail(new Error(`daemon did not answer within ${this.timeoutMs} ms`)));
            socket.on('close', () => this.fail(new Error('daemon closed the connection')));
            socket.once('error', reject);
            this.socket = socket;
        });
    }

    private onData(chunk: string): void {
        let frames: Frame[];
        try {
            frames = this.reader.push(chunk);
        } catch (e) {
            this.fail(e instanceof Error ? e : new Error(String(e)));
            return;
        }
        for (const frame of frames) {
            const waiter = this.waiter;
            if (waiter) {
                this.waiter = undefined;
                waiter.resolve(frame);
            } else {
                this.pending.push(frame);
            }
        }
    }

    private fail(error: Error): void {
        // Remember the first failure: a socket error usually arrives before 'close', and the later, vaguer
        // message would otherwise overwrite the one that explains what actually happened.
        this.failure ??= error;
        // ...and wake a read already waiting. Without this, a daemon that dies (or is killed, which is how a run
        // is stopped) mid-analysis left the read hanging until its timeout, ten minutes later.
        const waiter = this.waiter;
        if (waiter) {
            this.waiter = undefined;
            waiter.reject(this.failure);
        }
    }

    private send(message: Frame): void {
        if (!this.socket) throw new Error('not connected');
        this.socket.write(JSON.stringify(message) + '\n');
    }

    private nextFrame(): Promise<Frame> {
        const buffered = this.pending.shift();
        if (buffered) return Promise.resolve(buffered);
        if (this.failure) return Promise.reject(this.failure);
        return new Promise<Frame>((resolve, reject) => {
            const timer = setTimeout(() => {
                this.waiter = undefined;
                reject(new Error(`no frame from the daemon within ${this.timeoutMs} ms`));
            }, this.timeoutMs);
            this.waiter = {
                resolve: (frame) => {
                    clearTimeout(timer);
                    resolve(frame);
                },
                reject: (error) => {
                    clearTimeout(timer);
                    reject(error);
                },
            };
        });
    }

    async handshake(): Promise<Frame> {
        this.send({ type: 'handshake', protocolVersion: PROTOCOL_VERSION });
        return this.nextFrame();
    }

    /**
     * Run an analysis. Non-terminal frames (progress, and the streamed `partialResult`s) go to `onFrame` as
     * they arrive; the returned promise resolves with the terminal `result` or `error`.
     *
     * The loop is what makes streaming work without a protocol change on the client side: anything that is
     * not terminal is simply handed on, so a frame type added later flows through untouched.
     */
    async analyze(requestId: string, config: AnalyzeConfig, onFrame: (frame: Frame) => void): Promise<Frame> {
        this.send({ type: 'analyzeProject', requestId, config });
        for (;;) {
            const frame = await this.nextFrame();
            if (frame.type === 'result' || frame.type === 'error') return frame;
            onFrame(frame);
        }
    }

    dispose(): void {
        this.socket?.destroy();
        this.socket = undefined;
    }
}
