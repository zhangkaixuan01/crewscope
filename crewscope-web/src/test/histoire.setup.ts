import { defineSetupVue3 } from '@histoire/plugin-vue'

export const setupVue3 = defineSetupVue3(() => {
  // Global Histoire providers can be installed here when domain components need them.
})

export function setupVanilla(): void {
  // Histoire loads vanilla support alongside Vue; keep its setup boundary explicit.
}
