/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as assert from 'assert';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { test } from 'node:test';
import { pathToFileURL } from 'url';
import { folderSpellings, respell, respellAll } from '../paths';

/** A workspace opened through a symbolic link: the daemon's real paths, spelled back as the workspace has them. */

const links = (p: string) => p.replace(/^\/var\//, '/private/var/');

test('only a folder reached through a link needs spelling again', () => {
    assert.deepStrictEqual(folderSpellings(['/var/w/Demo', '/home/me/Other'], links),
        [{ spelled: '/var/w/Demo', real: '/private/var/w/Demo' }]);
    assert.deepStrictEqual(folderSpellings(['/home/me/Other'], links), []);
});

test('a file under the real folder is spelled as the workspace spells it; any other stays', () => {
    const spellings = folderSpellings(['/var/w/Demo'], links);
    assert.strictEqual(respell('file:///private/var/w/Demo/src/demo/Box.java', spellings),
        'file:///var/w/Demo/src/demo/Box.java');
    assert.strictEqual(respell('file:///private/var/w/DemoX/Box.java', spellings),
        'file:///private/var/w/DemoX/Box.java', 'a sibling with a longer name is not under the folder');
    assert.strictEqual(respell('jar:file:///lib.jar!/A.class', spellings), 'jar:file:///lib.jar!/A.class');
    assert.strictEqual(respell('file:///private/var/w/Demo/src/a%20b/C.java', spellings),
        'file:///var/w/Demo/src/a%20b/C.java');
});

test('every uri in a result, nested causes and steps included', () => {
    const spellings = folderSpellings(['/var/w/Demo'], links);
    const real = 'file:///private/var/w/Demo/src/X.java';
    const result = respellAll({
        elementAnnotations: [{ uri: real, why: [{ uri: real, line: 3 }] }],
        findings: [{ uri: real, causes: [{ uri: real, causes: [{ uri: 'file:///elsewhere/Y.java' }] }] }],
    }, spellings);
    assert.deepStrictEqual(result, {
        elementAnnotations: [{ uri: 'file:///var/w/Demo/src/X.java', why: [{ uri: 'file:///var/w/Demo/src/X.java', line: 3 }] }],
        findings: [{
            uri: 'file:///var/w/Demo/src/X.java',
            causes: [{ uri: 'file:///var/w/Demo/src/X.java', causes: [{ uri: 'file:///elsewhere/Y.java' }] }],
        }],
    });
});

test('on this machine: a folder opened through a real symbolic link', (t) => {
    const base = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'maddi-paths-')));
    t.after(() => fs.rmSync(base, { recursive: true, force: true }));
    fs.mkdirSync(path.join(base, 'real', 'src'), { recursive: true });
    const link = path.join(base, 'link');
    fs.symlinkSync(path.join(base, 'real'), link, 'dir');
    const spellings = folderSpellings([link]);
    const file = pathToFileURL(path.join(base, 'real', 'src', 'X.java')).href;
    assert.strictEqual(respell(file, spellings), pathToFileURL(path.join(link, 'src', 'X.java')).href);
});
