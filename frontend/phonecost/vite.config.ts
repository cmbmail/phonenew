import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return

          // All antd + @ant-design + rc-* go into one chunk.
          // Antd has extensive internal circular dependencies (icons ↔ core ↔ form ↔ feedback ↔ table ↔ layout ↔ display).
          // Splitting them into separate chunks causes "Cannot access before initialization" errors.
          // Keeping them together eliminates all circular chunk warnings.
          if (id.includes('antd') || id.includes('@ant-design') || id.includes('rc-')) {
            return 'vendor-antd'
          }

          if (id.includes('react') && !id.includes('react-i18next') && !id.includes('react-query')) return 'vendor-react-core'
          if (id.includes('@tanstack/react-query')) return 'vendor-query'
          if (id.includes('i18next') || id.includes('react-i18next')) return 'vendor-i18n'
          if (id.includes('axios') || id.includes('zustand') || id.includes('dayjs')) return 'vendor-misc'
        }
      }
    }
  }
})
