package io.crewscope.infrastructure.persistence.taskruntime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Read-only scalar mapping used to join Task collection rows with responsibility summaries. */
@Entity
@Immutable
@Table(name = "task_responsibility_snapshot_entry", schema = "crewscope")
@IdClass(TaskResponsibilitySnapshotEntryEntity.Key.class)
class TaskResponsibilitySnapshotEntryEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false)
    UUID snapshotId;

    @Id
    @Column(name = "assignment_id", nullable = false)
    UUID assignmentId;

    @Column(name = "principal_id", nullable = false)
    UUID principalId;

    @Column(name = "principal_type", nullable = false)
    String principalType;

    @Column(nullable = false)
    String role;

    protected TaskResponsibilitySnapshotEntryEntity() {}

    /** Composite identity of one immutable snapshot entry. */
    public static final class Key implements Serializable {
        private UUID snapshotId;
        private UUID assignmentId;

        public Key() {}

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(snapshotId, key.snapshotId)
                    && Objects.equals(assignmentId, key.assignmentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, assignmentId);
        }
    }
}
