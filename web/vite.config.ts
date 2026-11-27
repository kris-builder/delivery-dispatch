import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv, searchForWorkspaceRoot } from 'vite'

const repository = fileURLToPath(new URL('../', import.meta.url))

export default defineConfig(({ mode }) => {
  const local = loadEnv(mode, repository, 'DELIVERY_')
  const apiPort = local.DELIVERY_API_PORT || '8080'

  return {
    plugins: [vue()],
    build: {
      rolldownOptions: {
        input: {
          main: fileURLToPath(new URL('./index.html', import.meta.url)),
          planning: fileURLToPath(new URL('./planning-draft.html', import.meta.url)),
        },
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      fs: {
        allow: [searchForWorkspaceRoot(process.cwd()), fileURLToPath(new URL('../fixtures/', import.meta.url))],
      },
      proxy: {
        '/api': `http://127.0.0.1:${apiPort}`,
      },
    },
  }
})
