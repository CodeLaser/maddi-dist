/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import { ElementAnnotation } from './analysisModel';

/**
 * Keeps the computed annotations on their declarations while the file is edited.
 *
 * The analysis places an element at the line and column of the source it read. One line typed above a field
 * used to leave the field's hint on the line before it, and a deleted method's hint on whatever took its place.
 * IntelliJ and Eclipse find each declaration again by name (`owner` and `signature`), with the compiler's
 * bindings at hand to make the erased signature. VS Code offers no bindings, and jdt.ls's symbols give unerased
 * simple names (`add(E)`), so this front-end follows the edits instead, as an editor's range marker does: every
 * change VS Code reports moves what lies after it, and removes what it deleted.
 *
 * Imports no `vscode`, so it is unit-testable: positions here are VS Code's (0-based); an element's are maddi's
 * (1-based).
 */

/** One replaced range and the text that replaced it, as in `TextDocumentContentChangeEvent`. 0-based. */
export interface TextChange {
    startLine: number;
    startCol: number;
    endLine: number;
    endCol: number;
    text: string;
}

/**
 * The elements after one change: moved when they lie after it, unchanged before it, and dropped when the change
 * deleted their position (a declaration renamed or removed: its values belong to the name that is gone).
 */
export function follow(elements: ElementAnnotation[], change: TextChange): ElementAnnotation[] {
    const out: ElementAnnotation[] = [];
    for (const element of elements) {
        if (element.beginLine === undefined || element.beginLine < 1) {
            out.push(element); // no position: nothing to move
            continue;
        }
        const begin = move(element.beginLine - 1, (element.beginCol ?? 1) - 1, change);
        if (!begin) continue;
        const moved: ElementAnnotation = { ...element, beginLine: begin.line + 1, beginCol: begin.col + 1 };
        if (element.endLine !== undefined && element.endLine >= 1) {
            // the end only describes the extent; when the change swallowed it, it collapses onto the change's end
            const end = move(element.endLine - 1, Math.max(0, (element.endCol ?? 1) - 1), change)
                ?? endOf(change);
            moved.endLine = end.line + 1;
            moved.endCol = end.col + 1;
        }
        out.push(moved);
    }
    return out;
}

/** Several changes of one event, in the order VS Code reports them. */
export function followAll(elements: ElementAnnotation[], changes: TextChange[]): ElementAnnotation[] {
    return changes.reduce(follow, elements);
}

interface Point {
    line: number;
    col: number;
}

/** Where a point is after the change; undefined when the change deleted it. */
function move(line: number, col: number, change: TextChange): Point | undefined {
    const { startLine, startCol, endLine, endCol } = change;
    // before the change: untouched
    if (line < startLine || (line === startLine && col < startCol)) return { line, col };
    const inserted = change.text.split(/\r\n|\r|\n/);
    const added = inserted.length - 1;
    // after the change's end line: only the line count moves
    if (line > endLine) return { line: line + added - (endLine - startLine), col };
    // on the change's end line, at or after its end: what was after the change stays after the inserted text.
    // An insertion (start = end) at the point itself moves it along, as typing before a word does.
    if (line === endLine && col >= endCol) {
        const last = inserted[inserted.length - 1].length;
        const newCol = (added === 0 ? startCol + last : last) + (col - endCol);
        return { line: startLine + added, col: newCol };
    }
    return undefined; // inside the replaced range
}

function endOf(change: TextChange): Point {
    const inserted = change.text.split(/\r\n|\r|\n/);
    const added = inserted.length - 1;
    const last = inserted[inserted.length - 1].length;
    return { line: change.startLine + added, col: added === 0 ? change.startCol + last : last };
}
