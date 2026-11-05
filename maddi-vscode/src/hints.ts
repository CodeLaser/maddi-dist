/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as vscode from 'vscode';
import { Certainty, ElementAnnotation, certaintyLabel } from './analysisModel';
import { LIBRARY_NOTE, Line, STEP_PREFIX, lines, prefixOf } from './gutterText';
import { DEFAULT_HINT_FILTER, HintFilter, textFor } from './hintFilter';
import { ResultStore } from './results';

/**
 * The computed annotations, drawn in the editor as inlay hints.
 *
 * A deliberate divergence from the other two front-ends: they put a declaration's annotations on a line of
 * their own ABOVE it (IntelliJ `AboveLineIndentedPosition`, Eclipse `LineHeaderCodeMining`), which reads
 * like hand-written annotated API source. VS Code has no equivalent — an `InlayHint` is positioned at a
 * `Position` and renders within the line; nothing in the API adds a line. The only route to above-the-line
 * is injecting `display: block` CSS through a decoration's `textDecoration`, which is unsupported and
 * version-fragile, so it is not used here.
 *
 * So everything is inline, placed BEFORE the declaration it describes and padded, which is the closest
 * inline reading of the same idea: `@Container class Box`, `add(@Modified Mutable m)`.
 */
export class MaddiInlayHintsProvider implements vscode.InlayHintsProvider {

    private readonly changed = new vscode.EventEmitter<void>();
    readonly onDidChangeInlayHints = this.changed.event;

    constructor(private readonly store: ResultStore) {
        // a new analysis result — including one streamed mid-run — means the hints must be recomputed
        store.onDidChange(() => this.changed.fire());
    }

    provideInlayHints(
        document: vscode.TextDocument,
        range: vscode.Range,
        _token: vscode.CancellationToken): vscode.InlayHint[] {

        const filter = currentFilter();
        if (filter === 'none') return [];
        // how settled these values are: the same annotations mean something different mid-run, or after a
        // run that never reached a fixpoint, and only this says which
        const certainty = this.store.certainty();

        const hints: vscode.InlayHint[] = [];
        const all = this.store.annotationsFor(document.uri);
        for (const element of all) {
            const position = positionOf(document, element);
            // VS Code asks for one visible range at a time; anything outside it is somebody else's call
            if (!position || !range.contains(position)) continue;
            const text = textFor(filter, element);
            if (text.length === 0) continue;

            const hint = new vscode.InlayHint(position, text, kindOf(element));
            hint.paddingRight = true; // it precedes the declaration, so it must not touch it
            hint.tooltip = tooltip(element, all, certainty);
            hints.push(hint);
        }
        return hints;
    }

    dispose(): void {
        this.changed.dispose();
    }
}

function currentFilter(): HintFilter {
    return vscode.workspace.getConfiguration('maddi').get<HintFilter>('hints') ?? DEFAULT_HINT_FILTER;
}

/**
 * maddi counts lines and columns from 1, VS Code from 0. A position past the end of the document is
 * dropped rather than clamped: the file has been edited since the analysis, so the hint would be wrong
 * wherever it landed.
 */
function positionOf(document: vscode.TextDocument, element: ElementAnnotation): vscode.Position | undefined {
    if (element.beginLine === undefined || element.beginLine < 1) return undefined;
    const line = element.beginLine - 1;
    if (line >= document.lineCount) return undefined;
    const column = Math.max(0, (element.beginCol ?? 1) - 1);
    // clamp within the line only: a column past its end is an edit, not a reason to lose the hint
    return document.validatePosition(new vscode.Position(line, column));
}

function kindOf(element: ElementAnnotation): vscode.InlayHintKind {
    return element.kind === 'PARAMETER' ? vscode.InlayHintKind.Parameter : vscode.InlayHintKind.Type;
}

/**
 * The full computed picture on hover: what IntelliJ's gutter and Eclipse's ruler say ({@link lines}), so each
 * annotation's why — and for a method, its parameters with theirs — then how settled it is, and the raw
 * properties, which include what the filter left out.
 */
function tooltip(element: ElementAnnotation, all: ElementAnnotation[], certainty: Certainty): vscode.MarkdownString {
    const md = new vscode.MarkdownString();
    md.appendMarkdown(`**${escape(element.fqn)}**\n\n`);
    const caveat = certaintyLabel(certainty);
    if (caveat) md.appendMarkdown(`_${escape(caveat)}_\n\n`);
    const shown = lines(element, all);
    if (shown.length > 0) md.appendMarkdown(shown.map(render).join('  \n') + '\n\n');
    const properties = Object.entries(element.properties ?? {}).filter(([k]) => k !== LIBRARY_NOTE);
    if (properties.length > 0) {
        md.appendMarkdown('---\n\n' + properties.map(([k, v]) => `- \`${k}\` = ${escape(v)}`).join('\n'));
    }
    return md;
}

/**
 * One line, indented with non-breaking spaces (markdown collapses ordinary ones). A why's step with a place links
 * to it: VS Code's opener reads `#L<line>,<column>` on a file link as where to put the cursor.
 */
function render(line: Line): string {
    const indent = '\u00a0'.repeat(2 * line.depth);
    if (line.kind === 'HEADING') return `${indent}**${escape(line.text)}**`;
    const link = line.link;
    if (link && line.text.startsWith(link.place)) {
        const target = `${link.uri}#L${link.line},${Math.max(1, link.column)}`;
        return `${indent}${escape(STEP_PREFIX)}[${escape(link.place)}](${target})`
            + escape(line.text.substring(link.place.length));
    }
    return `${indent}${escape(prefixOf(line.kind))}${escape(line.text)}`;
}

/** Markdown-escape analysis text: annotations, signatures and code fragments are full of `*`, `_`, `<` and `[`. */
function escape(text: string): string {
    return text.replace(/[\\`*_{}\[\]()<>#+\-.!|]/g, (c) => '\\' + c);
}
