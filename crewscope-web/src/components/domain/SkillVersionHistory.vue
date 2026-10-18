<script setup lang="ts">
import type { SkillVersion } from '../../domains/skill/types'
import type { SkillResource, SkillVersionPageResource } from '../../domains/skill/store'
import type { Etagged } from '../../domains/settings/types'
import BaseVersionHistory from '../shared/BaseVersionHistory.vue'

/** Skill twin of the version history: the shared shell plus the rollback affordance. */
defineProps<{
  history: SkillVersionPageResource
  versionResource: SkillResource<Etagged<SkillVersion>> | null | undefined
  effective: SkillResource<Etagged<SkillVersion>> | null | undefined
  effectiveRevision: number | null
  selectedRevision: number | null
  online: boolean
  canManage: boolean
  canRollback: boolean
  rollingBack: boolean
}>()

const emit = defineEmits<{
  select: [revision: number]
  loadMore: []
  retry: []
  rollback: [revision: number]
}>()
</script>

<template>
  <BaseVersionHistory
    :history="history"
    :version-resource="versionResource"
    :effective="effective"
    :effective-revision="effectiveRevision"
    :selected-revision="selectedRevision"
    :online="online"
    subject="Skill"
    no-effective-note="当前没有生效版本：Skill 草稿未发布或已被禁用。"
    :can-manage="canManage"
    :can-rollback="canRollback"
    :rolling-back="rollingBack"
    @select="revision => emit('select', revision)"
    @load-more="emit('loadMore')"
    @retry="emit('retry')"
    @rollback="revision => emit('rollback', revision)"
  />
</template>
