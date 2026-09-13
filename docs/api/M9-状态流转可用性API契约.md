# M9-A05：WorkItem 状态流转可用性 API 契约

## Endpoint

`GET /api/v1/organizations/{organizationId}/teams/{teamId}/work-projects/{projectId}/work-items/{workItemId}/transitions/availability`

接口返回当前成员对该 WorkItem 的状态流转可发现性。请求必须通过既有 Team Membership 与 WorkProject 可见性校验；成员身份由服务端会话解析。

## Response

```json
{
  "transitions": [
    {
      "actionId": "submit-review",
      "targetStatus": "IN_REVIEW",
      "label": "提交评审",
      "strength": "PRIMARY",
      "reversible": true,
      "enabled": true,
      "reason": null,
      "reasonMessage": null,
      "remedyLabel": null,
      "remedyRoute": null
    }
  ]
}
```

`transitions` 只包含既有 `WorkItem` 状态机允许的边，顺序按目标状态稳定排序。不可用动作仍返回，便于前端解释原因；`reason` 使用稳定枚举，不能返回 Provider、数据库或权限系统原文。

当前实现覆盖状态机、项目权限和外部 Provider 托管三类运行期裁决；其余原因枚举已冻结，待 Review/Gate 前置事实接入时复用同一响应形状。可发现性信息不构成授权边界，实际转移仍由 `WorkItemCommandService` 重新执行权限、状态、来源、版本和幂等校验。
