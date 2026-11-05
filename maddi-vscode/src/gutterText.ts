/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import { ElementAnnotation, WhyStep } from './analysisModel';

/**
 * What an element's hover says, as lines: the annotations, where a library element's values come from, each
 * annotation's why (the chain of statements, the element's own first), and for a method its parameters with
 * theirs.
 *
 * A port of `GutterText` in `maddi-ide-client`, which IntelliJ's gutter and Eclipse's ruler render, so that all
 * three front-ends say the same. Keep the two in step. Imports no `vscode`, so it is unit-testable.
 */

export const ANNOTATIONS_PREFIX = 'maddi: ';
export const STEP_PREFIX = '⇐ ';

/** The key, among a placed library element's properties, of the note on where its values come from. */
export const LIBRARY_NOTE = 'maddi.libraryNote';

export type LineKind = 'ANNOTATIONS' | 'NOTE' | 'HEADING' | 'STEP' | 'PARAMETER';

export interface Line {
    depth: number;
    kind: LineKind;
    text: string;
    /**
     * A STEP with a place: where it is, so the hover can link to it, and the `place` prefix of `text` that names
     * it. Not in the Java original, whose gutters render plain text.
     */
    link?: { uri: string; line: number; column: number; place: string };
}

/**
 * The lines for `element`; empty when it has nothing to show. `all` holds the elements of the same file, where a
 * method's parameters are found.
 */
export function lines(element: ElementAnnotation, all: ElementAnnotation[]): Line[] {
    const parameters = parametersOf(all, element);
    const own = element.displayAnnotations ?? [];
    if (own.length === 0 && parameters.length === 0) return [];
    const out: Line[] = [{ depth: 0, kind: 'ANNOTATIONS', text: own.join(' ') }];
    const note = noteOf(element);
    if (note !== undefined) out.push({ depth: 1, kind: 'NOTE', text: note });
    addWhys(out, element, 1);
    for (const p of parameters) {
        const name = p.fqn.substring(p.fqn.lastIndexOf(':') + 1);
        out.push({ depth: 1, kind: 'PARAMETER', text: `${name}: ${(p.displayAnnotations ?? []).join(' ')}` });
        const parameterNote = noteOf(p);
        if (parameterNote !== undefined && parameterNote !== note) {
            out.push({ depth: 2, kind: 'NOTE', text: parameterNote });
        }
        addWhys(out, p, 2);
    }
    return out;
}

/** The lines as plain text: two spaces per depth, one line each. */
export function plain(lines: Line[]): string {
    return lines.map((l) => '  '.repeat(l.depth) + prefixOf(l.kind) + l.text).join('\n');
}

export function prefixOf(kind: LineKind): string {
    switch (kind) {
        case 'ANNOTATIONS': return ANNOTATIONS_PREFIX;
        case 'STEP': return STEP_PREFIX;
        default: return '';
    }
}

/**
 * A method's parameters that have something to show, in declaration order. A parameter's `fqn` is the method's,
 * then `:index:name`.
 */
export function parametersOf(elements: ElementAnnotation[], method: ElementAnnotation): ElementAnnotation[] {
    if (method.kind !== 'METHOD' || !method.fqn || !elements) return [];
    const prefix = method.fqn + ':';
    return elements
        .filter((a) => a.kind === 'PARAMETER' && !!a.fqn && a.fqn.startsWith(prefix)
            && a.fqn.indexOf(':', prefix.length) > prefix.length
            && (a.displayAnnotations?.length ?? 0) > 0)
        .sort((a, b) => parameterIndex(a.fqn, prefix.length) - parameterIndex(b.fqn, prefix.length));
}

function parameterIndex(fqn: string, from: number): number {
    const n = Number.parseInt(fqn.substring(from, fqn.indexOf(':', from)), 10);
    return Number.isNaN(n) ? Number.MAX_SAFE_INTEGER : n;
}

/** `line 9` in the element's own file, `Other.java:9` elsewhere, empty for a step without a place. */
export function whereOf(elementUri: string | undefined, step: WhyStep): string {
    if (!step.line || step.line <= 0) return '';
    if (!step.uri || step.uri === elementUri) return `line ${step.line}`;
    return `${step.uri.substring(step.uri.lastIndexOf('/') + 1)}:${step.line}`;
}

function addWhys(out: Line[], element: ElementAnnotation, depth: number): void {
    for (const why of element.whys ?? []) {
        if (!why.steps || why.steps.length === 0) continue;
        out.push({ depth, kind: 'HEADING', text: `${why.negative ? 'not @' : '@'}${why.annotation}:` });
        for (const step of why.steps) {
            // a step without a place (a contract, an annotation) is its text alone
            const place = whereOf(element.uri, step);
            const line: Line = { depth: depth + 1, kind: 'STEP', text: (place.length === 0 ? '' : place + ': ') + step.text };
            const uri = step.uri ?? element.uri;
            if (place.length > 0 && uri) line.link = { uri, line: step.line, column: step.column, place };
            out.push(line);
        }
    }
}

function noteOf(element: ElementAnnotation): string | undefined {
    return element.properties?.[LIBRARY_NOTE];
}
