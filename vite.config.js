import { defineConfig } from 'vite';

// MeshRouter build config.
// - `base: './'` so the built bundle works from any sub-path (GitHub Pages / Capacitor WebView / file://).
// - Output goes to `dist/`, which is what both GitHub Pages and Capacitor consume.
export default defineConfig({
  base: './',
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2019',
    sourcemap: false,
    rollupOptions: {
      input: {
        app: 'index.html',
        captive: 'captive.html'
      }
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: false,
    allowedHosts: true
  },
  preview: {
    host: '0.0.0.0',
    port: 4173,
    allowedHosts: true
  }
});
