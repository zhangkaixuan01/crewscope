package io.crewscope.application.organization;

/** Persistence Port for the full-set organization member directory read (M13b-A01). */
public interface OrganizationMemberDirectoryRepository {

    /**
     * Resolves one page of this organization's ACTIVE account bindings — account identity and
     * status joined with the principal's display name and the ACTIVE organization role grants —
     * in the stable (sort key, principal id) ordering.
     */
    OrganizationMemberDirectoryPage search(OrganizationMemberDirectoryQuery query);
}
