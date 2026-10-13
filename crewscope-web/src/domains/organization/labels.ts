import type {
  DeletionItemState,
  DeletionJobStatus,
  DeletionStepKey,
  ExportItemState,
  ExportJobStatus,
  ExportResourceFamily,
  OrganizationRole,
  OrganizationStatus,
  RoleGrantStatus,
} from './types'

/** 中文标签层：模板一律经 labels 取值（check-web-enum-labels 门禁禁裸枚举插值）。 */

export const organizationRoleLabels: Record<OrganizationRole, string> = {
  ORG_OWNER: '组织 Owner',
  ORG_ADMIN: '组织管理员',
  ORG_AUDITOR: '组织审计员',
}

export const organizationStatusLabels: Record<OrganizationStatus, string> = {
  ACTIVE: '正常',
}

export const roleGrantStatusLabels: Record<RoleGrantStatus, string> = {
  ACTIVE: '有效',
  REVOKED: '已撤销',
}

export const exportJobStatusLabels: Record<ExportJobStatus, string> = {
  PENDING: '待处理',
  RUNNING: '处理中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

export const exportItemStateLabels: Record<ExportItemState, string> = {
  PENDING: '待处理',
  DONE: '已导出',
  SKIPPED: '已跳过',
}

export const deletionJobStatusLabels: Record<DeletionJobStatus, string> = {
  PENDING_CONFIRMATION: '待确认',
  CONFIRMED: '已确认',
  RUNNING: '执行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
}

export const deletionItemStateLabels: Record<DeletionItemState, string> = {
  PENDING: '待执行',
  DONE: '完成',
  CONFLICT: '冲突',
  DENIED: '拒绝',
}

export const deletionStepLabels: Record<DeletionStepKey, string> = {
  REVOKE_TASK_CREDENTIAL_GRANTS: '回收任务凭证授权',
  REVOKE_SUBJECT_CREDENTIALS: '吊销主体凭证',
  TOMBSTONE_ACCOUNT_ARTIFACTS: '账号制品墓碑化',
  DELETE_DERIVED_ROWS: '删除派生数据行',
  TOMBSTONE_IDENTITY: '身份墓碑化',
  RECORD_BACKUP_RETENTION: '记录备份保留声明',
}

export const exportFamilyLabels: Record<ExportResourceFamily, string> = {
  ORGANIZATION: '组织',
  TEAM: '团队',
  TEAM_MEMBER: '团队成员',
  TEAM_ROLE: '团队角色',
  TEAM_MEMBER_ROLE: '成员角色授予',
  WORKSPACE: '工作区',
  WORK_PROJECT: '工作项目',
  WORK_ITEM: '工作项',
  WORK_ITEM_RELATION: '工作项关联',
  TASK: '任务',
  TASK_EXECUTION: '任务执行',
  RESPONSIBILITY_ASSIGNMENT: '责任分配',
  KNOWLEDGE_ENTRY: '知识条目',
  KNOWLEDGE_ENTRY_VERSION: '知识条目版本',
  KNOWLEDGE_ENTRY_EMBEDDING: '知识向量',
  REPOSITORY_INDEX_GENERATION: '仓库索引生成',
  REPOSITORY_CHUNK_EMBEDDING: '仓库分块向量',
  ARTIFACT_REFERENCE: '制品引用',
  TASK_CREDENTIAL_GRANT: '任务凭证授权',
  CREDENTIAL_SECRET: '凭证密钥（仅引用）',
  LOCAL_CREDENTIAL: '本地凭证（仅引用）',
  USER_ACCOUNT: '用户账号（仅引用）',
  ACCOUNT_ORGANIZATION_BINDING: '账号组织绑定',
  AUDIT_EVENT: '审计事件',
}
