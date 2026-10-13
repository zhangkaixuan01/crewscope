package io.crewscope.domain.organization.role;

/**
 * Capabilities an {@link OrganizationRole} can imply (S01 freeze section 3.6).
 *
 * <p>The vocabulary is orthogonal to {@code PlatformRole} and {@code TeamPermission}: an
 * organization capability never substitutes for a team-level write check, and vice versa.
 */
public enum OrganizationCapability {
    /** Read organization-level audit and usage surfaces. */
    AUDIT_READ_ORG,
    /** Read organization settings. */
    SETTINGS_READ,
    /** Write organization settings (including the audit retention field, S01 section 3.6 admin row). */
    SETTINGS_WRITE,
    /** Assign ORG_ADMIN/ORG_AUDITOR grants. */
    ROLE_ASSIGN,
    /** Assign ORG_OWNER grants (owner bailout). */
    ROLE_ASSIGN_OWNER,
    /** Revoke ORG_OWNER grants (owner bailout). */
    ROLE_REVOKE_OWNER,
    /** Initiate and steer organization data-export governance jobs. */
    GOVERNANCE_EXPORT,
    /** Initiate, confirm and run account-deletion governance jobs. */
    GOVERNANCE_DELETION
}
