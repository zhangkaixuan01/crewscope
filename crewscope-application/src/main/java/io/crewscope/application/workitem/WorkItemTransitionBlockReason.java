package io.crewscope.application.workitem;

/** Stable, non-sensitive reasons why a WorkItem transition is disabled. */
public enum WorkItemTransitionBlockReason {
  STATUS_NOT_ALLOWED("当前状态不允许此操作"),
  PERMISSION_DENIED("当前成员没有执行此操作的权限"),
  REVIEWER_REQUIRED("需要先指派 Reviewer"),
  DUTY_SEPARATION_CONFLICT("当前成员不能同时承担冲突职责"),
  GATE_NOT_PASSED("前置人工决策尚未通过"),
  BLOCKED_BY_DEPENDENCY("存在未解决的阻塞项"),
  EXTERNAL_PROVIDER_MANAGED("此工作项由外部 Provider 管理"),
  ARCHIVED("工作项已归档");

  private final String message;

  WorkItemTransitionBlockReason(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
