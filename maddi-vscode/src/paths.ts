/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */

import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';

/**
 * The daemon reports each file by its real path; VS Code keeps the spelling the workspace folder was opened with.
 * When that folder is reached through a symbolic link (on macOS every temporary directory is: /var is a link to
 * /private/var) the two differ, and a hint or a problem for /private/var/.../X.java never reaches the editor of
 * /var/.../X.java. So every location in a result is spelled again as the workspace spells it.
 *
 * Kept free of the vscode module, so that the unit tests can run it.
 */
export interface FolderSpelling {
    /** The folder as the workspace has it. */
    spelled: string;
    /** The same folder with every link resolved, as the daemon reports it. */
    real: string;
}

/** The folders whose real path differs from their spelling, the deepest first; empty when no link is involved. */
export function folderSpellings(folders: string[],
                                realpath: (p: string) => string = fs.realpathSync.native): FolderSpelling[] {
    const out: FolderSpelling[] = [];
    for (const spelled of folders) {
        let real: string;
        try {
            real = realpath(spelled);
        } catch {
            continue; // a folder that is gone has nothing to show
        }
        if (real !== spelled) out.push({ spelled, real });
    }
    return out.sort((a, b) => b.real.length - a.real.length);
}

/** A {@code file:} URI under one of the real folders, spelled as the workspace spells it; any other unchanged. */
export function respell(uri: string, spellings: FolderSpelling[]): string {
    if (spellings.length === 0 || !uri.startsWith('file:')) return uri;
    let file: string;
    try {
        file = fileURLToPath(uri);
    } catch {
        return uri;
    }
    for (const { spelled, real } of spellings) {
        if (file === real || file.startsWith(real + path.sep)) {
            return pathToFileURL(spelled + file.substring(real.length)).href;
        }
    }
    return uri;
}

/**
 * Every {@code uri} in a result, wherever it sits (elements, findings and their causes, the steps of a why), spelled
 * as the workspace spells it. In place: a result is fresh from the wire and has no other reader yet.
 */
export function respellAll<T>(value: T, spellings: FolderSpelling[]): T {
    if (spellings.length > 0) walk(value, spellings);
    return value;
}

function walk(value: unknown, spellings: FolderSpelling[]): void {
    if (Array.isArray(value)) {
        for (const item of value) walk(item, spellings);
    } else if (value !== null && typeof value === 'object') {
        const record = value as Record<string, unknown>;
        for (const [key, field] of Object.entries(record)) {
            if (key === 'uri' && typeof field === 'string') record[key] = respell(field, spellings);
            else walk(field, spellings);
        }
    }
}
