<script setup lang="ts">
import '../design/tokens.css'
import '../design/base.css'
import OnboardingWorkspace from './auth/OnboardingWorkspace.vue'
</script>

<template>
  <Story title="M7/First Team Onboarding" :layout="{ type: 'single', iframe: true, width: 1280 }">
    <Variant title="Team required">
      <OnboardingWorkspace phase="required" team-name="Platform Engineering" can-edit />
    </Variant>
    <Variant title="Atomic initialization">
      <OnboardingWorkspace phase="verifying" team-name="Platform Engineering" current-stage="workspace" />
    </Variant>
    <Variant title="Projection recovery">
      <OnboardingWorkspace
        phase="error"
        team-name="Platform Engineering"
        current-stage="agent"
        :problem="{
          code: 'onboarding_projection_unavailable',
          title: '工作入口仍在同步',
          message: '团队已创建，请重新检查工作空间和 Personal Agent。',
          tone: 'warning',
        }"
      />
    </Variant>
    <Variant title="Ready">
      <OnboardingWorkspace
        phase="complete"
        team-name="Platform Engineering"
        current-stage="ready"
        personal-agent-name="张凯旋的 Personal Agent"
      />
    </Variant>
  </Story>
</template>
