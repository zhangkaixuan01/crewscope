# M3-I07 耐久 AgentRun 事件映射

> 状态：已完成
> 日期：2026-08-15
> 范围：`crewscope-domain`、`crewscope-application`、`crewscope-agentscope`、`crewscope-infrastructure`、`crewscope-server`

## 交付结果

M3 Task Runtime 已形成从 AgentScope 有限事件流到 PostgreSQL 领域事实的原子提交闭环：

```text
TaskExecutionEvent
  -> 完整内存事件 SHA-256
  -> AgentRun 行锁 + Segment 连续序号
  -> ExecutionLease 行锁 + Owner/Fencing + PostgreSQL 权威时间
  -> AgentRun / AgentInterrupt 变化
  -> 受控 AGENT_RUN_EVENT_RECORDED
  -> Outbox
  -> agent_run_event_receipt
```

上述变化使用同一 REQUIRED 事务。DomainEvent、Outbox 或 Receipt 任一写入失败时，AgentRun 终态和 AgentInterrupt 也一并回滚。

## 序号与重放

- `agent_run_event_receipt` 以 Organization、AgentRun、Segment Sequence 和 Event Sequence 为主键；
- Repository 先使用 `SELECT ... FOR UPDATE` 锁定 AgentRun，再计算当前 Segment 的下一序号；
- 新事件必须严格等于 `max(event_sequence) + 1`；
- 同坐标、同指纹返回 `DUPLICATE` 和原 DomainEvent ID，不重复更新 Run、Interrupt 或 Outbox；
- 同坐标、不同指纹以及事件缺口失败关闭。

精确重放只返回既有 Receipt，不产生新写入。新事件在同一提交事务内使用
`SELECT ... FOR UPDATE` 锁定 ExecutionLease，然后用 PostgreSQL 权威时间校验
TaskExecution、attempt、Runtime、Worker、Claim Token Hash 和 Fencing Token。Heartbeat、显式
Release 或过期 Sweeper 不能跨过该事务边界；已释放、已过期或坐标不匹配的
Worker 不能新建 AgentRun 事件事实。

DomainEvent ID 和 Idempotency Key 由 AgentRun、Segment 和 Event Sequence 确定生成。Runtime 事件时间作为 Payload 事实保留，DomainEvent `occurredAt` 使用 PostgreSQL 权威事务时间。

## 中断与恢复

Approval 和 Pause 都携带服务端生成的不透明 Token。持久层只保存 Token SHA-256，创建 Pending AgentInterrupt 并将当前 AgentRun Segment 收敛为 `INTERRUPTED`。

`DurableAgentRunResumeService` 校验 Organization、Run、Interrupt、Token、ResumeRequestId、回答指纹和当前 Principal。成功后解析 Interrupt、开启下一 `RESUME` Segment 并写入 `AGENT_RUN_RESUMED`。同 Request ID 与同回答指纹精确重放，同 Request ID 不同回答失败关闭。

## 公开事件与脱敏

`AGENT_RUN_EVENT_RECORDED` 只允许：

- TaskExecution、attempt、AgentRun、Segment 和 Event Sequence；
- 安全文本、Tool 稳定名、状态、进度与成功标记；
- RuntimeArtifact/PlanVersion 引用和内容 Hash；
- Usage 累计值；
- Retry/Fallback 类型、Primary/Fallback 角色、Attempt 和 MaxAttempts；
- 安全错误分类、可重试性、安全消息和稳定 Runtime Code。

公开契约没有 Interrupt Token、ToolCallId、Tool 参数、原始 Tool Result、Structured Output 值、Provider 原始错误、AgentState 和私有推理字段。完整字段仍参与事件指纹，因此 Token 或 ToolCallId 变化会被判定为冲突重放。

## AgentScope 观测与边界

Task Agent 通过独立 Reactor Context Scope 观测模型 Retry 与 Fallback。该 Scope 与 M2 `AgentCallObservationScope` 并存，但只向 TaskExecutionEvent 输出受控转换事件。M2 Conversation 继续使用 RuntimeInvocationId 和遥测记录，不创建 M3 AgentRun 收件回执。

## 验证

专项测试覆盖：

- 连续序号、精确重放、同序号冲突和缺口；
- Approval/Pause 中断、Token Hash、Resume 及回答冲突；
- Completed 终态与 DomainEvent 写入失败回滚；
- 事务内 Lease 行锁、旧 Owner/过期 Owner 新事件拒绝；
- ToolCallId 和 Interrupt Token 脱敏，Artifact 与 SafeFailure 受控披露；
- Usage、Retry 和 Fallback 的稳定公开事实；
- 真实 PostgreSQL V10 迁移、事件收件行锁、MANDATORY 事务与回滚。

验证命令：

```bash
/Users/zhangkaixuan/Downloads/apache-maven-3.9.6/bin/mvn clean verify
node scripts/check-doc-links.mjs
git diff --check
```
