package io.crewscope.domain.shared.error;

/** Stable machine-readable domain error codes. */
public enum DomainErrorCode {
  INVALID_VALUE("invalid_value", DomainErrorCategory.VALIDATION),
  RULE_VIOLATION("rule_violation", DomainErrorCategory.VALIDATION),
  POLICY_DENIED("policy_denied", DomainErrorCategory.POLICY),
  INVALID_STATE_TRANSITION("invalid_state_transition", DomainErrorCategory.CONFLICT),
  AGGREGATE_NOT_FOUND("aggregate_not_found", DomainErrorCategory.NOT_FOUND),
  OPTIMISTIC_LOCK_CONFLICT("optimistic_lock_conflict", DomainErrorCategory.CONFLICT),
  IDENTITY_MAPPING_CONFLICT("identity_mapping_conflict", DomainErrorCategory.CONFLICT),
  ACCOUNT_IDENTIFIER_CONFLICT("account_identifier_conflict", DomainErrorCategory.CONFLICT),
  LOGIN_IDENTITY_CONFLICT("login_identity_conflict", DomainErrorCategory.CONFLICT),
  LOCAL_CREDENTIAL_CONFLICT("local_credential_conflict", DomainErrorCategory.CONFLICT),
  ACCOUNT_ORGANIZATION_BINDING_CONFLICT(
      "account_organization_binding_conflict", DomainErrorCategory.CONFLICT),
  TEAM_INVITATION_CONFLICT("team_invitation_conflict", DomainErrorCategory.CONFLICT),
  RESPONSIBILITY_CONFLICT("responsibility_conflict", DomainErrorCategory.CONFLICT),
  REPOSITORY_BINDING_KEY_CONFLICT(
      "repository_binding_key_conflict", DomainErrorCategory.CONFLICT),
  CODING_TARGET_SNAPSHOT_REVISION_CONFLICT(
      "coding_target_snapshot_revision_conflict", DomainErrorCategory.CONFLICT),
  EXECUTION_WORKSPACE_ATTEMPT_CONFLICT(
      "execution_workspace_attempt_conflict", DomainErrorCategory.CONFLICT),
  DIFF_ARTIFACT_WORKSPACE_CONFLICT(
      "diff_artifact_workspace_conflict", DomainErrorCategory.CONFLICT),
  COMMAND_EVIDENCE_SEQUENCE_CONFLICT(
      "command_evidence_sequence_conflict", DomainErrorCategory.CONFLICT),
  TEST_EVIDENCE_SEQUENCE_CONFLICT(
      "test_evidence_sequence_conflict", DomainErrorCategory.CONFLICT),
  CODING_CHECKPOINT_SEQUENCE_CONFLICT(
      "coding_checkpoint_sequence_conflict", DomainErrorCategory.CONFLICT),
  WORK_PROJECT_KEY_CONFLICT("work_project_key_conflict", DomainErrorCategory.CONFLICT),
  WORK_ITEM_KEY_CONFLICT("work_item_key_conflict", DomainErrorCategory.CONFLICT),
  IDEMPOTENCY_CONFLICT("idempotency_conflict", DomainErrorCategory.CONFLICT);

  private final String value;
  private final DomainErrorCategory category;

  DomainErrorCode(String value, DomainErrorCategory category) {
    this.value = value;
    this.category = category;
  }

  public String value() {
    return value;
  }

  public DomainErrorCategory category() {
    return category;
  }
}
