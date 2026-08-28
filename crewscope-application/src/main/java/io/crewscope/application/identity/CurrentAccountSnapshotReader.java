package io.crewscope.application.identity;

import io.crewscope.domain.identity.UserAccountId;
import java.util.Optional;

/** One-query persistence port for the non-secret current-account aggregate graph. */
public interface CurrentAccountSnapshotReader {

    Optional<CurrentAccountSnapshot> findByAccountId(UserAccountId accountId);
}
