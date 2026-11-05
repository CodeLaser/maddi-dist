/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as assert from 'assert';
import { test } from 'node:test';
import { ElementAnnotation, Why } from '../analysisModel';
import { LIBRARY_NOTE, lines, plain } from '../gutterText';

/**
 * The hover text, ported from `GutterTextTest` in maddi-ide-client: the same element gives the same lines in all
 * three front-ends, so the cases are the Java test's, one for one.
 */

const URI = 'file:///p/src/x/Bag.java';

function element(kind: string, fqn: string, display: string[], whys: Why[] = [],
                 properties: Record<string, string> = {}, uri: string = URI): ElementAnnotation {
    return {
        uri, beginLine: 1, beginCol: 1, endLine: 1, endCol: 1,
        kind, fqn, displayAnnotations: display, annotations: [], properties, whys,
    };
}

test('each annotation\'s why under it, then a method\'s parameters with theirs', () => {
    const add = element('METHOD', 'x.Bag.add(String)', ['@Modified'], [{
        annotation: 'Modified', steps: [
            { uri: URI, line: 9, column: 9, text: 'calls store(..)' },
            { uri: 'file:///p/src/x/Store.java', line: 12, column: 9, text: 'calls items.add(..)' },
            { line: 0, column: 0, text: 'a contract' },
        ],
    }]);
    const s = element('PARAMETER', 'x.Bag.add(String):0:s', ['@NotModified'], [{
        annotation: 'NotModified', steps: [{ uri: URI, line: 8, column: 9, text: 'read only' }],
    }]);

    const result = lines(add, [add, s]);
    assert.deepStrictEqual(result.map(({ depth, kind, text }) => ({ depth, kind, text })), [
        { depth: 0, kind: 'ANNOTATIONS', text: '@Modified' },
        { depth: 1, kind: 'HEADING', text: '@Modified:' },
        { depth: 2, kind: 'STEP', text: 'line 9: calls store(..)' },
        { depth: 2, kind: 'STEP', text: 'Store.java:12: calls items.add(..)' },
        { depth: 2, kind: 'STEP', text: 'a contract' },
        { depth: 1, kind: 'PARAMETER', text: 's: @NotModified' },
        { depth: 2, kind: 'HEADING', text: '@NotModified:' },
        { depth: 3, kind: 'STEP', text: 'line 8: read only' },
    ]);
    assert.strictEqual(plain(result), [
        'maddi: @Modified',
        '  @Modified:',
        '    ⇐ line 9: calls store(..)',
        '    ⇐ Store.java:12: calls items.add(..)',
        '    ⇐ a contract',
        '  s: @NotModified',
        '    @NotModified:',
        '      ⇐ line 8: read only',
    ].join('\n'));
    // a step with a place links to it; the step in another file to that file; a step without one to nothing
    assert.deepStrictEqual(result.filter((l) => l.kind === 'STEP').map((l) => l.link), [
        { uri: URI, line: 9, column: 9, place: 'line 9' },
        { uri: 'file:///p/src/x/Store.java', line: 12, column: 9, place: 'Store.java:12' },
        undefined,
        { uri: URI, line: 8, column: 9, place: 'line 8' },
    ]);
});

test('why a type is not an annotation it does not show: a negative why', () => {
    const bag = element('TYPE', 'x.Bag', ['@FinalFields'], [{
        annotation: 'Container', negative: true,
        steps: [{ uri: URI, line: 5, column: 9, text: 'parameter list of fill(..) is modified: calls list.add(..)' }],
    }]);
    assert.strictEqual(plain(lines(bag, [bag])), [
        'maddi: @FinalFields',
        '  not @Container:',
        '    ⇐ line 5: parameter list of fill(..) is modified: calls list.add(..)',
    ].join('\n'));
});

test('a library note follows the annotations; a parameter\'s only when it differs from its method\'s', () => {
    const annotated = { [LIBRARY_NOTE]: 'from the annotated API' };
    const get = element('METHOD', 'java.util.List.get(int)', ['@NotModified'], [], annotated);
    const same = element('PARAMETER', 'java.util.List.get(int):0:index', ['@Independent'], [], annotated);
    const other = element('PARAMETER', 'java.util.List.get(int):1:x', ['@NotModified'], [],
        { [LIBRARY_NOTE]: 'a default' });
    assert.strictEqual(plain(lines(get, [get, other, same])), [
        'maddi: @NotModified',
        '  from the annotated API',
        '  index: @Independent',
        '  x: @NotModified',
        '    a default',
    ].join('\n'));
});

test('nothing to show: no lines; a method with annotated parameters only: an empty first line', () => {
    const m = element('METHOD', 'x.Bag.m(int)', []);
    assert.deepStrictEqual(lines(m, [m]), []);
    const p = element('PARAMETER', 'x.Bag.m(int):0:i', ['@NotModified']);
    assert.strictEqual(plain(lines(m, [m, p])), 'maddi: \n  i: @NotModified');
});

test('an older daemon\'s element, without whys, still reads', () => {
    const t: ElementAnnotation = {
        uri: URI, kind: 'TYPE', fqn: 'x.Bag', displayAnnotations: ['@Container'], annotations: [], properties: {},
    };
    assert.strictEqual(plain(lines(t, [t])), 'maddi: @Container');
});
