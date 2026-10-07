import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The pilot is one module mounted by the existing page, not a second application
// with its own index.html. Library mode gives a single predictable filename that
// app.js can import() on demand, so nothing is downloaded unless the pilot is on.
export default defineConfig({
  plugins: [react()],
  // Library mode does not substitute this the way an app build does, so without
  // it React's development build ships: measured 1085 kB against 378 kB, with
  // its warning paths and slower reconciler.
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    outDir: '../src/main/resources/static/pilot',
    emptyOutDir: true,
    target: 'es2022',
    minify: 'esbuild',
    // No separate CSS file: the pilot deliberately inherits theme.css and app.css
    // from the host page, so it cannot drift into a second visual language.
    cssCodeSplit: false,
    lib: {
      entry: 'src/mount.tsx',
      formats: ['es'],
      fileName: () => 'practice.js',
    },
    rollupOptions: {
      // React is bundled: the host page has no module system and no React of its
      // own, and the pilot must not require a CDN at runtime.
      output: { inlineDynamicImports: true },
    },
  },
});
