declare const process: { env: { TECHNEXUS_API_PROXY_TARGET?: string } }

const apiProxyTarget = process.env.TECHNEXUS_API_PROXY_TARGET ?? 'http://localhost:8080/api/**'

const browserSecurityHeaders = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()'
}

export default defineNuxtConfig({
  compatibilityDate: '2026-01-15',
  devtools: { enabled: false },
  css: ['~/assets/main.css'],
  runtimeConfig: {
    public: {
      apiBase: '/api/v1'
    }
  },
  routeRules: {
    '/**': { headers: browserSecurityHeaders },
    '/api/**': { proxy: apiProxyTarget }
  },
  router: {
    options: {
      sensitive: true
    }
  },
  app: {
    head: {
      htmlAttrs: { lang: 'zh-CN' },
      title: 'TechNexus 技术协作平台',
      titleTemplate: '%s · TechNexus',
      meta: [
        { name: 'description', content: '从技术问题、结构化需求到可交付解决方案和可复用知识。' },
        { name: 'theme-color', content: '#145c42' },
        { property: 'og:type', content: 'website' }
      ]
    }
  },
  typescript: { strict: true, typeCheck: true }
})
