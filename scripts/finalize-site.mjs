import { cp, mkdir, rename, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

// Keep the Android bundle untouched: the website gets its own output folder.
// The compiled app is copied verbatim under /demo/ so all its ./asset URLs,
// manifest and service-worker scope remain correct on project GitHub Pages.
const root = process.cwd();
const site = join(root, 'site-dist');
await rename(join(site, 'landing.html'), join(site, 'index.html'));
await mkdir(join(site, 'demo'), { recursive: true });
await cp(join(root, 'dist'), join(site, 'demo'), { recursive: true });
await writeFile(join(site, '.nojekyll'), '');
console.log('Website ready: site-dist/index.html + site-dist/demo/index.html');
