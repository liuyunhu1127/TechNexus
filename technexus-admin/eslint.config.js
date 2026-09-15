import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**'] },
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
  { languageOptions: { globals: { document: 'readonly', sessionStorage: 'readonly', window: 'readonly' } } },
)
