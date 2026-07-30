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
    build: {
      // Served by the Xoboro server itself, so the asset paths must be relative
      // to the deployment root rather than absolute to the host root.
      outDir: 'dist',
      emptyOutDir: true,
    },
  }
})
