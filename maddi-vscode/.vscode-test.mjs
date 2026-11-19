// The UI tests (src/uitest): a real VS Code, downloaded into .vscode-test/, with redhat.java and this extension,
// on a copy of uitest-fixture/Demo. Run with `npm run uitest` (or `task vscode:uitest`).
import { defineConfig } from '@vscode/test-cli';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

// jdt.ls writes into the project (bin/, its metadata) and the tests edit the sources: work on a fresh copy.
// Deliberately NOT through realpath: on macOS the temporary directory is reached through a symbolic link
// (/var -> /private/var) and the daemon reports real paths, which the extension must spell back (src/paths.ts).
const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'maddi-vscode-uitest-'));
fs.cpSync(path.resolve('uitest-fixture/Demo'), workspace, { recursive: true });

export default defineConfig({
    files: 'out/uitest/**/*.test.js',
    workspaceFolder: workspace,
    version: process.env.VSCODE_TEST_VERSION ?? 'stable',
    installExtensions: ['redhat.java'],
    launchArgs: ['--disable-workspace-trust'],
    mocha: {
        ui: 'bdd',
        // jdt.ls must start and import the project, then the daemon must start and analyse
        timeout: 10 * 60 * 1000,
    },
});
