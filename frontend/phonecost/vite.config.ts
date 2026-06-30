import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('antd/es/')) {
              // Split antd by internal module groups
              if (id.includes('antd/es/table') || id.includes('antd/es/grid') || id.includes('antd/es/list') || id.includes('antd/es/pagination')) return 'vendor-antd-table';
              if (id.includes('antd/es/form') || id.includes('antd/es/input') || id.includes('antd/es/select') || id.includes('antd/es/checkbox') || id.includes('antd/es/radio') || id.includes('antd/es/switch') || id.includes('antd/es/date-picker') || id.includes('antd/es/upload') || id.includes('antd/es/tree') || id.includes('antd/es/tree-select') || id.includes('antd/es/cascader')) return 'vendor-antd-form';
              if (id.includes('antd/es/modal') || id.includes('antd/es/drawer') || id.includes('antd/es/popconfirm') || id.includes('antd/es/popover') || id.includes('antd/es/tooltip') || id.includes('antd/es/tag') || id.includes('antd/es/badge') || id.includes('antd/es/alert') || id.includes('antd/es/message') || id.includes('antd/es/notification') || id.includes('antd/es/result') || id.includes('antd/es/empty') || id.includes('antd/es/skeleton') || id.includes('antd/es/spin')) return 'vendor-antd-feedback';
              if (id.includes('antd/es/menu') || id.includes('antd/es/layout') || id.includes('antd/es/dropdown') || id.includes('antd/es/breadcrumb') || id.includes('antd/es/tabs') || id.includes('antd/es/steps') || id.includes('antd/es/avatar')) return 'vendor-antd-layout';
              if (id.includes('antd/es/statistic') || id.includes('antd/es/progress') || id.includes('antd/es/typography') || id.includes('antd/es/divider') || id.includes('antd/es/space') || id.includes('antd/es/row') || id.includes('antd/es/col') || id.includes('antd/es/card') || id.includes('antd/es/descriptions') || id.includes('antd/es/timeline')) return 'vendor-antd-display';
              return 'vendor-antd-core';
            }
            if (id.includes('@ant-design/icons')) return 'vendor-antd-icons';
            if (id.includes('rc-')) return 'vendor-rc';
            if (id.includes('react') && !id.includes('react-i18next') && !id.includes('react-query')) return 'vendor-react';
            if (id.includes('@tanstack/react-query')) return 'vendor-query';
            if (id.includes('i18next') || id.includes('react-i18next')) return 'vendor-i18n';
            if (id.includes('axios') || id.includes('zustand') || id.includes('dayjs')) return 'vendor-misc';
          }
        }
      }
    }
  }
})
