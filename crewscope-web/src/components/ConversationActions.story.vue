<script setup lang="ts">
import { ref } from 'vue'
import ConversationAgentActionRegion from './domain/ConversationAgentActionRegion.vue'
import TaskIntentCard from './domain/TaskIntentCard.vue'
import ConversationWorkItemLinks from './domain/ConversationWorkItemLinks.vue'
import { fixtureConversationWorkItemAssociation } from '../test/conversationWorkItemFixtures'
import { fixtureIds } from '../test/scopeFixtures'
import { fixtureTaskIntent } from '../test/taskIntentFixtures'

const lastAction = ref('等待操作')
const clarification = {
  schemaVersion: '1' as const,
  summary: '需要确定仓库和目标分支后继续规划。',
  questions: [
    { fieldKey: 'repository', question: '使用哪个仓库？', context: '选择 Team 已授权的仓库', required: true, choices: ['crewscope-java', 'agentscope-java'] },
    { fieldKey: 'branch', question: '使用哪个分支？', context: null, required: true, choices: [] },
  ],
}
</script>

<template>
  <Story title="Conversation/Task actions" :layout="{ type: 'grid', width: 820 }">
    <Variant title="Clarification">
      <ConversationAgentActionRegion
        phase="interrupted"
        status-text="Personal Agent 需要补充信息"
        invocation-id="story-invocation"
        :online="true"
        :retryable="false"
        :clarification="clarification"
        @cancel="lastAction = '取消调用'"
        @submit-clarification="answers => lastAction = JSON.stringify(answers)"
      />
      <p class="story-result">{{ lastAction }}</p>
    </Variant>
    <Variant title="Agent failure recovery">
      <ConversationAgentActionRegion
        phase="error"
        status-text="服务暂时无法完成请求"
        invocation-id="story-invocation"
        :online="true"
        :retryable="true"
        :clarification="null"
        @retry="lastAction = '重新连接'"
      />
      <p class="story-result">{{ lastAction }}</p>
    </Variant>
    <Variant title="TaskIntent owner review">
      <TaskIntentCard
        :intent="fixtureTaskIntent()"
        :current-principal-id="fixtureIds.principal"
        @confirm="lastAction = '确认预检'"
        @reject="reason => lastAction = reason"
        @revise="input => lastAction = input.objective"
      />
    </Variant>
    <Variant title="TaskIntent participant view">
      <TaskIntentCard :intent="fixtureTaskIntent()" :current-principal-id="fixtureIds.secondPrincipal" />
    </Variant>
    <Variant title="Confirmed WorkItem">
      <ConversationWorkItemLinks phase="ready" :associations="[fixtureConversationWorkItemAssociation]" direction="conversation" />
    </Variant>
    <Variant title="Linked Conversation">
      <ConversationWorkItemLinks phase="ready" :associations="[fixtureConversationWorkItemAssociation]" direction="work-item" />
    </Variant>
  </Story>
</template>

<style scoped>
.story-result { max-width: 740px; margin: 8px auto; color: var(--cs-text-muted); font-size: 10px; }
</style>
