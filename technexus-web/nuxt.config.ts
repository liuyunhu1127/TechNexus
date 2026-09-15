export default defineNuxtConfig({
  compatibilityDate: '2026-01-15',
  devtools: { enabled: false },
  css: ['~/assets/main.css'],
  runtimeConfig: {
    public: {
      apiBase: '/api/v1'
    }
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      titleTemplate: '%s · TechNexus',
      meta: [
        { name: 'description', content: '从技术问题、结构化需求到可交付解决方案和可复用知识。' }
      ]
    }
  },
  typescript: { strict: true, typeCheck: true }
})
