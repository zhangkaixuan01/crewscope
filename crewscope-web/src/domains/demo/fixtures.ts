export type SemanticTone = 'neutral' | 'info' | 'agent' | 'warning' | 'danger' | 'success'

export interface ResponsibilityMember {
  role: 'Owner' | 'Executor' | 'Reviewer'
  name: string
  kind: 'human' | 'agent'
  detail: string
}

export interface AgentSnapshot {
  name: string
  type: string
  status: 'running' | 'waiting' | 'offline'
  step: string
  runtime: string
}

export interface WorkItemSnapshot {
  key: string
  title: string
  status: 'in_progress' | 'review' | 'blocked'
  owner: string
  executor: string
  updatedAt: string
}

export const statusPresentation: Record<WorkItemSnapshot['status'], { label: string; tone: SemanticTone }> = {
  in_progress: { label: '进行中', tone: 'info' },
  review: { label: '待 Review', tone: 'warning' },
  blocked: { label: '已阻塞', tone: 'danger' },
}

export const demoResponsibilities: ResponsibilityMember[] = [
  { role: 'Owner', name: '张凯旋', kind: 'human', detail: '对交付结果负责' },
  { role: 'Executor', name: 'Coding Agent', kind: 'agent', detail: '正在执行计划' },
  { role: 'Reviewer', name: '林晨', kind: 'human', detail: '合并前技术 Review' },
]

export const demoAgent: AgentSnapshot = {
  name: 'Coding Agent',
  type: 'Specialist Agent',
  status: 'running',
  step: '运行领域测试并整理变更证据',
  runtime: 'AgentScope 2.0 · Docker Sandbox',
}

export const demoWorkItems: WorkItemSnapshot[] = [
  { key: 'CRW-18', title: '建立 GitHub Provider 连接与仓库绑定', status: 'in_progress', owner: '张凯旋', executor: 'Coding Agent', updatedAt: '刚刚' },
  { key: 'CRW-15', title: '确认团队任务的责任转移规则', status: 'review', owner: '林晨', executor: 'Personal Agent', updatedAt: '18 分钟前' },
  { key: 'CRW-12', title: '补齐沙箱执行网络策略', status: 'blocked', owner: '王博', executor: 'Platform Agent', updatedAt: '1 小时前' },
]
