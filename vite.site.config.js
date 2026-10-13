import { defineConfig } from 'vite';
import { resolve } from 'node:path';

// Separate website build: the default `vite build` must keep index.html as
// the Capacitor app entry. Relative assets work under /MeshRouter/ on Pages.
export default defineConfig({
  base: './',
  build: {
    outDir: 'site-dist',
    emptyOutDir: true,
    target: 'es2019',
    sourcemap: false,
    rollupOptions: { input: { landing: resolve(process.cwd(), 'landing.html') } }
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
    strictPort: true,
    allowedHosts: true
  }
});
