import { defineConfig } from 'histoire'
import { HstVue } from '@histoire/plugin-vue'

export default defineConfig({
  plugins: [HstVue()],
  setupFile: './src/test/histoire.setup.ts',
  // Histoire beta expects generated setup exports for every active support plugin.
  setupCode: [
    'export function setupVue3() {}\nexport function setupVanilla() {}',
  ],
  storyMatch: ['**/*.story.vue'],
  theme: {
    title: 'CrewScope UI',
    logo: {
      square: './src/design/crewscope-mark.svg',
      light: './src/design/crewscope-mark.svg',
      dark: './src/design/crewscope-mark.svg',
    },
    colors: {
      primary: {
        50: '#f0fbf4',
        100: '#dff6e7',
        200: '#b8efca',
        300: '#8ed5a7',
        400: '#66b684',
        500: '#43845e',
        600: '#3f7257',
        700: '#315944',
        800: '#263a31',
        900: '#15231d',
      },
    },
  },
})
