package io.crewscope.application.availability;

/**
 * Stable, non-sensitive reasons why an action is disabled.
 *
 * <p>One vocabulary for every object type that offers actions. A per-object copy of these constants
 * would let the same situation be explained differently on two surfaces — the drift M9 forbids —
 * so the projector of each object type reports into this enum instead of declaring its own.
 *
 * <p>Not every constant is reachable from every object type, and a constant with no rule anywhere is
 * a product rule that does not exist yet rather than a wiring gap. The per-object reachability table
 * lives in {@code docs/api/M9-状态流转可用性API契约.md}; {@link #EXTERNAL_PROVIDER_MANAGED} and
 * {@link #ARCHIVED} are emitted by WorkItems alone, which is why their messages say so.
 */
public enum TransitionBlockReason {
  STATUS_NOT_ALLOWED("当前状态不允许此操作"),
  PERMISSION_DENIED("当前成员没有执行此操作的权限"),
  REVIEWER_REQUIRED("需要先指派 Reviewer"),
  DUTY_SEPARATION_CONFLICT("当前成员不能同时承担冲突职责"),
  GATE_NOT_PASSED("前置人工决策尚未通过"),
  BLOCKED_BY_DEPENDENCY("存在未解决的阻塞项"),
  EXTERNAL_PROVIDER_MANAGED("此工作项由外部 Provider 管理"),
  ARCHIVED("工作项已归档");

  private final String message;

  TransitionBlockReason(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
