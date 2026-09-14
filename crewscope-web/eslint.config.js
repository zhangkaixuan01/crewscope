import tsParser from '@typescript-eslint/parser'
import vue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'

/**
 * E01 keeps lint configuration intentionally small while the legacy pages are
 * being split. New violations fail immediately; migration-specific rules can
 * be enabled one group at a time without hiding existing product behaviour.
 */
export default [
  {
    ignores: ['dist/**', 'coverage/**', 'node_modules/**', 'e2e/**', 'src/spikes/**', 'src/**/*.story.vue', 'src/api/generated/**'],
  },
  // Essential Vue correctness rules only; stylistic formatting is handled by the
  // existing formatter gate while legacy SFCs are incrementally reflowed in E01.
  ...vue.configs['flat/essential'],
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    },
    rules: {
      'no-debugger': 'error',
      'no-console': 'off',
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { ecmaVersion: 'latest', sourceType: 'module', parser: tsParser },
    },
    rules: {
      'no-debugger': 'error',
      'no-console': 'off',
      'vue/multi-word-component-names': 'off',
      'vue/no-unused-vars': 'error',
    },
  },
]
