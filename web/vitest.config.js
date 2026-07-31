import { defineConfig } from 'vitest/config'
import { svelte } from '@sveltejs/vite-plugin-svelte'

export default defineConfig({
  plugins: [svelte()],
  // Without this, Node's export conditions resolve Svelte's server build and
  // every component test fails on `lifecycle_function_unavailable` — onMount and
  // $effect do not exist during SSR, so nothing that has either can be rendered.
  resolve: { conditions: ['browser'] },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./tests/setup.js'],
    include: ['tests/**/*.test.js'],
    restoreMocks: true,
  },
})
