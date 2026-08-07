package io.crewscope.infrastructure.event;

import io.crewscope.domain.shared.time.UtcTimestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL Outbox leasing adapter using row locks that do not block competing publishers. */
@Repository
public class JdbcOutboxClaimStore implements OutboxClaimStore {

    private static final String EXPIRED_CLAIM_ERROR = "CLAIM_EXPIRED";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcDomainEventJsonMapper eventJsonMapper;

    public JdbcOutboxClaimStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.eventJsonMapper = new JdbcDomainEventJsonMapper(
                Objects.requireNonNull(objectMapper, "objectMapper"));
    }

    @Override
    @Transactional
    public List<ClaimedOutboxEvent> claimAvailable(
            String workerId, Instant now, OutboxDeliveryPolicy policy) {
        String publisher = requireWorkerId(workerId);
        Instant claimTime = Objects.requireNonNull(now, "now");
        OutboxDeliveryPolicy deliveryPolicy = Objects.requireNonNull(policy, "policy");
        reapExpiredClaims(claimTime, deliveryPolicy);

        OffsetDateTime claimedAt = utc(claimTime);
        OffsetDateTime expiresAt = utc(claimTime.plus(deliveryPolicy.claimLease()));
        return jdbcTemplate.query(
                """
                WITH candidates AS (
                    SELECT candidate.id
                    FROM crewscope.outbox_event candidate
                    JOIN crewscope.domain_event candidate_event
                      ON candidate_event.event_id = candidate.domain_event_id
                    WHERE candidate.delivery_status = 'PENDING'
                      AND candidate.retry_count < ?
                      AND (candidate.next_delivery_at IS NULL OR candidate.next_delivery_at <= ?)
                      AND NOT EXISTS (
                          SELECT 1
                          FROM crewscope.outbox_event predecessor
                          JOIN crewscope.domain_event predecessor_event
                            ON predecessor_event.event_id = predecessor.domain_event_id
                          WHERE predecessor.topic = candidate.topic
                            AND predecessor.partition_key = candidate.partition_key
                            AND predecessor.delivery_status IN ('PENDING', 'CLAIMED')
                            AND (
                                predecessor_event.aggregate_version,
                                predecessor_event.occurred_at,
                                predecessor_event.event_id
                            ) < (
                                candidate_event.aggregate_version,
                                candidate_event.occurred_at,
                                candidate_event.event_id
                            )
                      )
                    ORDER BY candidate.created_at, candidate.id
                    FOR UPDATE OF candidate SKIP LOCKED
                    LIMIT ?
                )
                UPDATE crewscope.outbox_event claimed
                SET delivery_status = 'CLAIMED',
                    claim_token = gen_random_uuid(),
                    claimed_by = ?,
                    claim_expires_at = ?,
                    next_delivery_at = NULL,
                    version = claimed.version + 1,
                    updated_at = ?
                FROM candidates, crewscope.domain_event event
                WHERE claimed.id = candidates.id
                  AND event.event_id = claimed.domain_event_id
                RETURNING claimed.id AS outbox_id,
                          claimed.claim_token,
                          claimed.topic,
                          claimed.partition_key,
                          claimed.retry_count,
                          event.event_id,
                          event.event_type,
                          event.schema_version,
                          event.organization_id,
                          event.team_id,
                          event.workspace_id,
                          event.subject_type,
                          event.subject_id,
                          event.aggregate_version,
                          event.actor_type,
                          event.actor_id,
                          event.correlation_id,
                          event.causation_id,
                          event.idempotency_key,
                          event.occurred_at,
                          event.payload::TEXT AS payload
                """,
                this::mapClaim,
                deliveryPolicy.maxAttempts(),
                claimedAt,
                deliveryPolicy.claimSize(),
                publisher,
                expiresAt,
                claimedAt);
    }

    @Override
    @Transactional
    public boolean markDelivered(UUID outboxId, UUID claimToken, Instant deliveredAt) {
        Instant completionTime = Objects.requireNonNull(deliveredAt, "deliveredAt");
        OffsetDateTime completed = utc(completionTime);
        return jdbcTemplate.update(
                        """
                        UPDATE crewscope.outbox_event
                        SET delivery_status = 'DELIVERED',
                            claim_token = NULL,
                            claimed_by = NULL,
                            claim_expires_at = NULL,
                            next_delivery_at = NULL,
                            delivered_at = ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ?
                          AND delivery_status = 'CLAIMED'
                          AND claim_token = ?
                          AND claim_expires_at > ?
                        """,
                        completed,
                        completed,
                        Objects.requireNonNull(outboxId, "outboxId"),
                        Objects.requireNonNull(claimToken, "claimToken"),
                        completed)
                == 1;
    }

    @Override
    @Transactional
    public boolean markFailed(
            UUID outboxId,
            UUID claimToken,
            Instant failedAt,
            String errorCode,
            OutboxDeliveryPolicy policy) {
        Instant failureTime = Objects.requireNonNull(failedAt, "failedAt");
        OutboxDeliveryPolicy deliveryPolicy = Objects.requireNonNull(policy, "policy");
        String stableErrorCode = requireErrorCode(errorCode);
        OffsetDateTime failed = utc(failureTime);
        return jdbcTemplate.update(
                        """
                        UPDATE crewscope.outbox_event
                        SET delivery_status = CASE
                                WHEN retry_count + 1 >= ? THEN 'DEAD_LETTER'
                                ELSE 'PENDING'
                            END,
                            retry_count = retry_count + 1,
                            next_delivery_at = CASE
                                WHEN retry_count + 1 >= ? THEN NULL
                                ELSE ? + (
                                    LEAST(?, ? * POWER(2.0, LEAST(retry_count, 30)))
                                    * INTERVAL '1 millisecond'
                                )
                            END,
                            claim_token = NULL,
                            claimed_by = NULL,
                            claim_expires_at = NULL,
                            last_error_code = ?,
                            version = version + 1,
                            updated_at = ?
                        WHERE id = ?
                          AND delivery_status = 'CLAIMED'
                          AND claim_token = ?
                          AND claim_expires_at > ?
                        """,
                        deliveryPolicy.maxAttempts(),
                        deliveryPolicy.maxAttempts(),
                        failed,
                        deliveryPolicy.maximumBackoff().toMillis(),
                        deliveryPolicy.initialBackoff().toMillis(),
                        stableErrorCode,
                        failed,
                        Objects.requireNonNull(outboxId, "outboxId"),
                        Objects.requireNonNull(claimToken, "claimToken"),
                        failed)
                == 1;
    }

    private void reapExpiredClaims(Instant now, OutboxDeliveryPolicy policy) {
        OffsetDateTime reapedAt = utc(now);
        jdbcTemplate.update(
                """
                UPDATE crewscope.outbox_event
                SET delivery_status = 'DEAD_LETTER',
                    retry_count = retry_count + 1,
                    claim_token = NULL,
                    claimed_by = NULL,
                    claim_expires_at = NULL,
                    next_delivery_at = NULL,
                    last_error_code = ?,
                    version = version + 1,
                    updated_at = ?
                WHERE delivery_status = 'CLAIMED'
                  AND claim_expires_at <= ?
                  AND retry_count + 1 >= ?
                """,
                EXPIRED_CLAIM_ERROR,
                reapedAt,
                reapedAt,
                policy.maxAttempts());
        jdbcTemplate.update(
                """
                UPDATE crewscope.outbox_event
                SET delivery_status = 'PENDING',
                    retry_count = retry_count + 1,
                    claim_token = NULL,
                    claimed_by = NULL,
                    claim_expires_at = NULL,
                    next_delivery_at = ? + (
                        LEAST(?, ? * POWER(2.0, LEAST(retry_count, 30)))
                        * INTERVAL '1 millisecond'
                    ),
                    last_error_code = ?,
                    version = version + 1,
                    updated_at = ?
                WHERE delivery_status = 'CLAIMED'
                  AND claim_expires_at <= ?
                  AND retry_count + 1 < ?
                """,
                reapedAt,
                policy.maximumBackoff().toMillis(),
                policy.initialBackoff().toMillis(),
                EXPIRED_CLAIM_ERROR,
                reapedAt,
                reapedAt,
                policy.maxAttempts());
    }

    private ClaimedOutboxEvent mapClaim(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedOutboxEvent(
                resultSet.getObject("outbox_id", UUID.class),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("topic"),
                resultSet.getString("partition_key"),
                resultSet.getInt("retry_count"),
                UtcTimestamp.from(resultSet.getObject("occurred_at", OffsetDateTime.class)),
                eventJsonMapper.map(resultSet));
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String requireWorkerId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > OutboxClaimStore.MAX_WORKER_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "workerId must contain at most "
                            + OutboxClaimStore.MAX_WORKER_ID_LENGTH
                            + " characters");
        }
        return normalized;
    }

    private static String requireErrorCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("errorCode must contain at most 100 characters");
        }
        return normalized;
    }
}
