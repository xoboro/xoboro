import { defineConfig, loadEnv } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// The dev server proxies the native API to a locally running Xoboro so that the
// browser sees one origin. That matters for more than convenience: the native
// cookie transport is SameSite=Strict and its mutations require same-origin
// provenance, so a cross-origin dev setup could not authenticate at all.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.XOBORO_DEV_PROXY_TARGET || 'http://localhost:25600'

  return {
    plugins: [svelte()],
    server: {
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
          secure: env.XOBORO_DEV_PROXY_SECURE !== 'false',
        },
      },
    },
    // Relative asset URLs, because the server can be deployed under a context
    // path. Absolute "/assets/..." references would 404 under /xoboro.
    base: './',
    build: {
      outDir: 'dist',
      emptyOutDir: true,
      // Pinned rather than left to the default: lib/http.js derives the
      // deployment root by cutting this bundle's URL at "/assets/", so renaming
      // the directory would silently break every API call under a context path.
      assetsDir: 'assets',
    },
  }
})
