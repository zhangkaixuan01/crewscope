package io.crewscope.application.organization.governance;

import io.crewscope.domain.shared.id.OrganizationId;
import java.util.List;

/**
 * Read-side Port behind the organization data export: one bounded, keyset-positioned JSON page
 * per resource family plus the family row counts that pin job items at creation. Adapters own
 * the column whitelists — credential families project references only (never secret material),
 * artifact references project descriptors only (never blob content), and vector families encode
 * {@code embedding} as base64 little-endian float32 with dimension and model coordinates.
 */
public interface OrganizationExportResourceReader {

    /** Whether the embedding pipeline currently backs the two vector families. */
    boolean vectorStoreEnabled();

    /** Live row count of one family under the organization (job item pinning face). */
    long countFamily(OrganizationId organizationId, ExportResourceFamily family);

    /**
     * Reads up to {@code limit} rows of one family strictly after the opaque keyset cursor
     * (empty for the first page); the returned cursor is empty when the family is exhausted.
     */
    ExportResourcePage readFamily(
            OrganizationId organizationId,
            ExportResourceFamily family,
            String afterCursor,
            int limit);

    /** One bounded page: whitelisted rows as stable JSON text plus the continuation cursor. */
    record ExportResourcePage(List<String> rows, String nextCursor) {

        public ExportResourcePage {
            rows = List.copyOf(rows);
        }

        public boolean hasNext() {
            return nextCursor != null && !nextCursor.isBlank();
        }
    }
}
