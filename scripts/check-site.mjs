import assert from 'node:assert/strict';
import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';

const root = process.cwd();
const site = join(root, 'site-dist');
const page = await readFile(join(site, 'index.html'), 'utf8');
const demo = await readFile(join(site, 'demo/index.html'), 'utf8');
const app = await readFile(join(root, 'dist/index.html'), 'utf8');

assert.match(page, /href="\.\/demo\/"/);
assert.match(page, /src="\.\/demo\/\?preview=dashboard"/);
assert.match(page, /Demo w przeglądarce tylko pokazuje interfejs/);
assert.match(demo, /id="downloadDemo"/);
assert.match(demo, /id="demoTransferBtn"[^>]*data-action="demo-download"/);
assert.match(demo, /WYŁĄCZNIE DEMO/);
assert.match(demo, /id="nativeDownloadControls" hidden/);
assert.equal(app, demo, 'The published demo must be the actual browser app build, not a mock');

// Vite must emit relative URLs: Pages serves the project from /MeshRouter/,
// while Capacitor still loads the original dist/index.html at its own root.
for (const [html, prefix] of [[page, site], [demo, join(site, 'demo')]]) {
  const refs = [...html.matchAll(/(?:src|href)="(\.\/(?:assets|icons)\/[^"?]+)"/g)].map((match) => match[1]);
  assert.ok(refs.length >= 2, 'Expected local JS/CSS/icon assets');
  for (const ref of refs) assert.ok((await stat(join(prefix, ref))).isFile(), `Missing ${ref}`);
  assert.doesNotMatch(html, /(?:src|href)="\/assets\//, 'Absolute asset URLs break project Pages');
}
console.log('Site structure and /demo/ asset paths verified.');
