/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as assert from 'assert';
import { test } from 'node:test';
import { ElementAnnotation } from '../analysisModel';
import { TextChange, follow, followAll } from '../edits';

/** Hints that follow their declarations through edits, as a range marker does. */

/** An element at maddi's 1-based line and column, one line long. */
function at(line: number, col: number, fqn = 'x.Bag.items'): ElementAnnotation {
    return {
        uri: 'file:///X.java', beginLine: line, beginCol: col, endLine: line, endCol: col + 4,
        kind: 'FIELD', fqn, displayAnnotations: ['@Final'], annotations: [], properties: {},
    };
}

/** A change in VS Code's 0-based positions. */
function change(startLine: number, startCol: number, endLine: number, endCol: number, text: string): TextChange {
    return { startLine, startCol, endLine, endCol, text };
}

const where = (es: ElementAnnotation[]) => es.map((e) => [e.beginLine, e.beginCol, e.endLine, e.endCol]);

test('a line typed above moves the declaration down; one deleted above moves it up', () => {
    // field on maddi line 5, column 5 = VS Code line 4, column 4
    assert.deepStrictEqual(where(follow([at(5, 5)], change(1, 0, 1, 0, 'int x;\n'))), [[6, 5, 6, 9]]);
    assert.deepStrictEqual(where(follow([at(5, 5)], change(1, 0, 2, 0, ''))), [[4, 5, 4, 9]]);
});

test('an edit below leaves it; on its own line, only what lies after the edit moves', () => {
    assert.deepStrictEqual(where(follow([at(5, 5)], change(9, 0, 9, 0, '\n\n'))), [[5, 5, 5, 9]]);
    // typing on the declaration's line, before it: the declaration moves right
    assert.deepStrictEqual(where(follow([at(5, 5)], change(4, 0, 4, 0, '  '))), [[5, 7, 5, 11]]);
    // typing after its start, within its extent: the start stays
    assert.deepStrictEqual(where(follow([at(5, 5)], change(4, 6, 4, 6, 'zz'))), [[5, 5, 5, 11]]);
});

test('a line break before the declaration on its line takes it to the next line, at the new column', () => {
    // "    private" split after 2 of the indent: what was at column 4 is now at column 2 of the next line
    assert.deepStrictEqual(where(follow([at(5, 5)], change(4, 2, 4, 2, '\n'))), [[6, 3, 6, 7]]);
});

test('a deleted declaration loses its hint; its neighbours keep theirs', () => {
    const before = at(3, 5, 'x.Bag.a');
    const gone = at(5, 5, 'x.Bag.b');
    const after = at(7, 5, 'x.Bag.c');
    // delete VS Code lines 4..5 (maddi 5..6)
    const result = follow([before, gone, after], change(4, 0, 6, 0, ''));
    assert.deepStrictEqual(result.map((e) => e.fqn), ['x.Bag.a', 'x.Bag.c']);
    assert.deepStrictEqual(where(result), [[3, 5, 3, 9], [5, 5, 5, 9]]);
});

test('several changes of one event apply in turn', () => {
    // VS Code reports a multi-cursor edit from the end of the document backwards
    const result = followAll([at(2, 1), at(8, 1)], [change(5, 0, 5, 0, 'a\n'), change(0, 0, 0, 0, 'b\n')]);
    assert.deepStrictEqual(where(result), [[3, 1, 3, 5], [10, 1, 10, 5]]);
});

test('an element without a position is left alone', () => {
    const loose: ElementAnnotation = {
        uri: 'file:///X.java', kind: 'TYPE', fqn: 'x.Bag', displayAnnotations: [], annotations: [], properties: {},
    };
    assert.deepStrictEqual(follow([loose], change(0, 0, 3, 0, '')), [loose]);
});
