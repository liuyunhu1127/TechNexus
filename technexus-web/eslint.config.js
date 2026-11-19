import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['.nuxt/**', '.output/**', 'node_modules/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
    rules: {
      'vue/html-self-closing': 'off',
	  'vue/max-attributes-per-line': 'off',
      'vue/multi-word-component-names': 'off',
	  'vue/singleline-html-element-content-newline': 'off',
    },
  },
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: {
        AbortSignal: 'readonly',
        fetch: 'readonly',
        URL: 'readonly',
        WebSocket: 'readonly',
      },
    },
  },
  {
    languageOptions: {
      globals: {
        computed: 'readonly',
        crypto: 'readonly',
        defineNuxtPlugin: 'readonly',
        defineNuxtConfig: 'readonly',
        Event: 'readonly',
        File: 'readonly',
        HTMLInputElement: 'readonly',
        navigateTo: 'readonly',
        process: 'readonly',
        reactive: 'readonly',
		ref: 'readonly',
        useCookie: 'readonly',
		useApi: 'readonly',
		useAsyncData: 'readonly',
        useFetch: 'readonly',
		useRoute: 'readonly',
        useRuntimeConfig: 'readonly',
		useSeoMeta: 'readonly',
        window: 'readonly',
      },
    },
  },
)
