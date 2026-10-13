package io.crewscope.application.organization.governance;

import java.util.List;

/**
 * The v1 frozen coordination-step vocabulary of the account deletion workflow (S01 freeze 3.4).
 * Items are pinned per (stepKey, targetRef) with live expected counts at job creation and
 * settle in their own short transactions in declaration order.
 *
 * <p>FK inventory verdict (the plan-mandated D01 matrix sweep): principal/user_account physical
 * row deletion is blocked by the created_by/updated_by RESTRICT foreign keys spread over thirty
 * tables, and rewriting those audit references wholesale is its own migration. v1 therefore
 * tombstones the identity (login surfaces physically removed, account and principal rows
 * disabled in place) and declares physical row removal a later increment — the backup
 * retention statement travels with every job either way.
 */
public enum DeletionStepKey {
    REVOKE_TASK_CREDENTIAL_GRANTS,
    REVOKE_SUBJECT_CREDENTIALS,
    TOMBSTONE_ACCOUNT_ARTIFACTS,
    DELETE_DERIVED_ROWS,
    TOMBSTONE_IDENTITY,
    RECORD_BACKUP_RETENTION;

    /** The frozen backup disclosure written onto every deletion job (S01 freeze 3.4). */
    public static final String BACKUP_RETENTION_STATEMENT =
            "Row deletion is not full copy erasure. retain-backups.sh keeps daily snapshots for"
                    + " 7 days and weekly snapshots for 4 weeks; rows deleted before a snapshot"
                    + " may survive in backups for up to about 4 weeks. Restoring a backup from"
                    + " inside that window re-introduces deleted rows; after a restore, re-run"
                    + " process to reconcile (the job is resumable — already deleted steps"
                    + " converge as no-op DONE or CONFLICT). After the window the backups age"
                    + " out naturally.";

    /**
     * The v1 derived-row table list of DELETE_DERIVED_ROWS, pinned as one item per table in
     * foreign-key order: TEAM_MEMBER comes last because the member-scoped tables above it hold
     * RESTRICT references to it.
     */
    public static final List<String> DERIVED_ROW_TARGETS =
            List.of(
                    "TEAM_MEMBER_ROLE",
                    "NOTIFICATION_PREFERENCE",
                    "INBOX_DISPOSITION",
                    "CONVERSATION_PARTICIPANT",
                    "COMMAND_RESULT",
                    "TEAM_INVITATION",
                    "ORGANIZATION_ROLE_GRANT",
                    "TEAM_MEMBER");
}
