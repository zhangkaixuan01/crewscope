<script setup lang="ts">
import { BriefcaseBusiness, Check, ChevronDown, Layers3, RefreshCw, UsersRound } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useScopeStore } from '../../domains/scope/store'

const route = useRoute()
const router = useRouter()
const store = useScopeStore()
const open = ref(false)
const root = useTemplateRef<HTMLElement>('root')

const teamInitial = computed(() => store.selectedTeam.value?.name.slice(0, 1).toUpperCase() ?? 'T')
const scopeLabel = computed(() => store.selectedTeam.value?.name ?? '选择 Team')
const projectLabel = computed(() => store.selectedProject.value
  ? `${store.selectedProject.value.key} · ${store.selectedProject.value.name}`
  : store.state.phase === 'loading' ? '正在加载范围' : '全部项目')

async function chooseTeam(teamId: string): Promise<void> {
  open.value = false
  await router.push({
    query: { ...route.query, team: teamId, project: undefined, workItem: undefined, focus: undefined },
  })
}

async function chooseProject(projectId: string): Promise<void> {
  open.value = false
  await router.push({ query: { ...route.query, project: projectId, workItem: undefined, focus: undefined } })
}

function closeOnOutsideClick(event: MouseEvent): void {
  if (open.value && root.value && !root.value.contains(event.target as Node)) open.value = false
}

function closeOnEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('click', closeOnOutsideClick)
  document.addEventListener('keydown', closeOnEscape)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', closeOnOutsideClick)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div ref="root" class="scope-switcher-root">
    <button
      class="scope-switcher"
      type="button"
      aria-controls="scope-menu"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span class="scope-switcher__avatar">{{ teamInitial }}</span>
      <span class="scope-switcher__copy">
        <strong>{{ scopeLabel }}</strong>
        <small>{{ projectLabel }}</small>
      </span>
      <ChevronDown :class="{ rotated: open }" :size="14" aria-hidden="true" />
    </button>

    <section v-if="open" id="scope-menu" class="scope-menu" aria-label="切换团队和项目">
      <header>
        <div><span>工作范围</span><strong>Team 与 WorkProject</strong></div>
        <button type="button" aria-label="重新加载工作范围" @click="store.reload()"><RefreshCw :size="14" /></button>
      </header>

      <div v-if="store.state.phase === 'loading'" class="scope-menu__state" role="status">正在读取可访问范围…</div>
      <div v-else-if="store.state.phase === 'error'" class="scope-menu__state scope-menu__state--error" role="alert">
        {{ store.state.errorMessage }}
      </div>
      <div v-else-if="store.state.phase === 'empty'" class="scope-menu__state" role="status">当前账号还没有加入 Team。</div>
      <template v-else>
        <div class="scope-menu__group">
          <p><UsersRound :size="13" />Team</p>
          <button
            v-for="team in store.state.teams"
            :key="team.id"
            type="button"
            :class="{ selected: team.id === store.state.selectedTeamId }"
            @click="chooseTeam(team.id)"
          >
            <span>{{ team.name }}<small>{{ team.initializationStatus === 'READY' ? '团队工作区' : '等待初始化' }}</small></span>
            <Check v-if="team.id === store.state.selectedTeamId" :size="14" aria-hidden="true" />
          </button>
        </div>

        <div class="scope-menu__group scope-menu__projects">
          <p><BriefcaseBusiness :size="13" />WorkProject</p>
          <div v-if="store.state.projects.length === 0" class="scope-menu__state">这个 Team 还没有 WorkProject。</div>
          <button
            v-for="project in store.state.projects"
            :key="project.id"
            type="button"
            :class="{ selected: project.id === store.state.selectedProjectId }"
            @click="chooseProject(project.id)"
          >
            <i>{{ project.key.slice(0, 2) }}</i>
            <span>{{ project.name }}<small class="mono">{{ project.key }}</small></span>
            <Check v-if="project.id === store.state.selectedProjectId" :size="14" aria-hidden="true" />
          </button>
        </div>
      </template>

      <footer><Layers3 :size="13" />切换范围会保留当前入口，并清除不兼容的 Focus。</footer>
    </section>
  </div>
</template>

<style scoped>
.scope-switcher-root { position: relative; width: 100%; margin: 22px 0 18px; }
.scope-switcher { display: grid; width: 100%; min-width: 0; grid-template-columns: 30px minmax(0, 1fr) 14px; align-items: center; gap: 8px; padding: 9px; border: 1px solid #d4e2d7; border-radius: var(--cs-radius-md); background: rgb(255 255 255 / 88%); text-align: left; cursor: pointer; }
.scope-switcher__avatar { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: var(--cs-brand-200); color: var(--cs-brand-950); font-size: 12px; font-weight: 800; }
.scope-switcher__copy, .scope-switcher__copy strong, .scope-switcher__copy small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.scope-switcher__copy strong { font-size: 12px; }.scope-switcher__copy small { color: var(--cs-text-muted); font-size: 9px; font-weight: 500; }
.scope-switcher > svg { transition: transform var(--cs-transition-fast); }.scope-switcher > svg.rotated { transform: rotate(180deg); }
.scope-menu { position: absolute; z-index: 50; top: calc(100% + 8px); left: 0; width: 330px; max-height: min(620px, calc(100vh - 120px)); overflow: auto; border: 1px solid var(--cs-border-strong); border-radius: var(--cs-radius-lg); background: var(--cs-surface); box-shadow: var(--cs-shadow-float); }
.scope-menu header { position: sticky; z-index: 2; top: 0; display: flex; align-items: center; justify-content: space-between; padding: 14px 15px; border-bottom: 1px solid var(--cs-border); background: rgb(255 255 255 / 96%); }
.scope-menu header span, .scope-menu header strong { display: block; }.scope-menu header span { color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }.scope-menu header strong { font-size: 12px; }.scope-menu header button { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 7px; background: var(--cs-surface-subtle); color: var(--cs-text-muted); cursor: pointer; }
.scope-menu__group { padding: 9px; border-bottom: 1px solid var(--cs-border); }.scope-menu__group p { display: flex; align-items: center; gap: 6px; margin: 4px 6px 6px; color: var(--cs-text-muted); font-size: 9px; font-weight: 750; letter-spacing: .08em; text-transform: uppercase; }
.scope-menu__group > button { display: grid; width: 100%; grid-template-columns: 1fr auto; align-items: center; gap: 8px; padding: 9px; border-radius: var(--cs-radius-sm); background: transparent; text-align: left; cursor: pointer; }.scope-menu__group > button:hover { background: var(--cs-brand-50); }.scope-menu__group > button.selected { background: var(--cs-brand-100); color: var(--cs-brand-800); }.scope-menu__group button span, .scope-menu__group button small { display: block; }.scope-menu__group button span { overflow: hidden; font-size: 11px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }.scope-menu__group button small { color: var(--cs-text-muted); font-size: 9px; font-weight: 500; }
.scope-menu__projects > button { grid-template-columns: 28px 1fr auto; }.scope-menu__projects button i { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--cs-agent-soft); color: var(--cs-agent); font-size: 9px; font-style: normal; font-weight: 800; }
.scope-menu__state { padding: 14px; color: var(--cs-text-muted); font-size: 10px; text-align: center; }.scope-menu__state--error { color: var(--cs-danger); }
.scope-menu footer { display: flex; align-items: center; gap: 6px; padding: 10px 14px; color: var(--cs-text-muted); font-size: 9px; }
@media (max-width: 1100px) {
  .scope-switcher-root { width: auto; }.scope-switcher { grid-template-columns: 30px; width: auto; padding: 8px; }.scope-switcher__copy, .scope-switcher > svg { display: none; }.scope-menu { position: fixed; top: 72px; left: 68px; }
}
@media (max-width: 767px) {
  .scope-switcher-root { width: min(250px, calc(100vw - 70px)); margin: 0; }
  .scope-switcher { width: 100%; grid-template-columns: 28px minmax(0, 1fr) 14px; padding: 5px 8px; border-color: transparent; background: transparent; }
  .scope-switcher__avatar { width: 28px; height: 28px; }.scope-switcher__copy, .scope-switcher > svg { display: block; }
  .scope-menu { position: fixed; top: 50px; left: 8px; width: min(350px, calc(100vw - 16px)); max-height: calc(100vh - 122px); }
}
</style>
