/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as vscode from 'vscode';
import { Certainty, ElementAnnotation, Finding, Result, certaintyOf } from './analysisModel';
import { TextChange, followAll } from './edits';

/**
 * The latest analysis result, indexed for the display surfaces — the counterpart of `MaddiResults`
 * (Eclipse) and the cache inside `MaddiAnalysisService` (IntelliJ).
 *
 * Indexed by filesystem path rather than by URI string: maddi reports `file:///abs/X.java` and VS Code hands
 * out its own spelling of the same file, and the two are not reliably equal as strings.
 */
export class ResultStore {
    private latest: Result | undefined;
    /** What is drawn: {@link base} with the edits since applied ({@link followAll}). */
    private annotations = new Map<string, ElementAnnotation[]>();
    /** By path, at the positions of the source the run read. */
    private base = new Map<string, ElementAnnotation[]>();
    /** By path, the edits since that source was saved for a run, in order; and how many of them are on disk. */
    private edits = new Map<string, TextChange[]>();
    private savedEdits = new Map<string, number>();
    /** True while what is held came from a streamed pass rather than a finished run. */
    private provisional = false;

    private readonly changed = new vscode.EventEmitter<void>();
    /** Fires when the surfaces should re-read; also drives inlay-hint refresh. */
    readonly onDidChange = this.changed.event;

    /** A finished run: its outcome decides whether the values are final or merely the best available. */
    set(result: Result): void {
        this.provisional = false;
        this.replace(result);
    }

    /**
     * A view merged from streamed passes, mid-run. Held separately from a finished result because the
     * annotations look identical: without this, a hint shown after two passes would read exactly like one
     * from a certified fixpoint.
     */
    setPartial(result: Result): void {
        this.provisional = true;
        this.replace(result);
    }

    private replace(result: Result): void {
        this.latest = result;
        this.base = index(result.elementAnnotations ?? []);
        this.annotations = new Map();
        for (const [path, elements] of this.base) {
            this.annotations.set(path, followAll(elements, this.edits.get(path) ?? []));
        }
        this.changed.fire();
    }

    /**
     * A run is about to read the saved sources: from here on its positions are the reference. What is on screen
     * stays, at the positions it has been followed to, so the edits so far are folded into it and forgotten.
     */
    beginRun(): void {
        this.base = this.annotations;
        this.edits = new Map();
        this.savedEdits = new Map();
    }

    /** Edits to a file: its annotations follow them, and so will those of a result that is still to come. */
    edit(uri: vscode.Uri, changes: TextChange[]): void {
        if (changes.length === 0) return;
        const path = uri.fsPath;
        const list = this.edits.get(path);
        if (list) list.push(...changes);
        else this.edits.set(path, [...changes]);
        const shown = this.annotations.get(path);
        if (shown) {
            this.annotations.set(path, followAll(shown, changes));
            this.changed.fire();
        }
    }

    /** The file is saved: its edits so far are on disk, and survive the editor being closed. */
    saved(uri: vscode.Uri): void {
        this.savedEdits.set(uri.fsPath, this.edits.get(uri.fsPath)?.length ?? 0);
    }

    /**
     * The editor is closed. Edits that were never saved are gone with it (the file reopens as it is on disk), so
     * the annotations go back to following the saved ones only.
     */
    closed(uri: vscode.Uri): void {
        const path = uri.fsPath;
        const list = this.edits.get(path);
        const saved = this.savedEdits.get(path) ?? 0;
        if (!list || list.length <= saved) return;
        list.length = saved;
        const base = this.base.get(path);
        if (base) {
            this.annotations.set(path, followAll(base, list));
            this.changed.fire();
        }
    }

    clear(): void {
        this.provisional = false;
        this.latest = undefined;
        this.annotations = new Map();
        this.base = new Map();
        this.changed.fire();
    }

    get current(): Result | undefined {
        return this.latest;
    }

    findings(): Finding[] {
        return this.latest?.findings ?? [];
    }

    /**
     * How settled what is on screen is. A merged mid-run view carries no outcome, so it reads as UNKNOWN
     * rather than as final — which is what makes a streamed hint honest about still being able to strengthen.
     */
    certainty(): Certainty {
        if (this.provisional) return 'PROVISIONAL';
        return certaintyOf(this.latest);
    }

    annotationsFor(uri: vscode.Uri): ElementAnnotation[] {
        return this.annotations.get(uri.fsPath) ?? [];
    }

    dispose(): void {
        this.changed.dispose();
    }
}

function index(elements: ElementAnnotation[]): Map<string, ElementAnnotation[]> {
    const byPath = new Map<string, ElementAnnotation[]>();
    for (const element of elements) {
        if (!element.uri) continue;
        let path: string;
        try {
            path = vscode.Uri.parse(element.uri).fsPath;
        } catch {
            continue; // an element we cannot place is one we cannot draw
        }
        const list = byPath.get(path);
        if (list) list.push(element);
        else byPath.set(path, [element]);
    }
    return byPath;
}
