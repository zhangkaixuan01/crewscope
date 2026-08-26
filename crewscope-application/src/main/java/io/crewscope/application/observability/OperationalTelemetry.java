package io.crewscope.application.observability;

import java.util.Objects;

/**
 * Best-effort operational telemetry boundary shared by background workers and Agent runtimes.
 *
 * <p>The contract intentionally accepts only closed enums. Tenant, member, resource, URI, payload,
 * exception and credential values therefore cannot become metric labels through this port.
 */
public interface OperationalTelemetry {

    /** Starts one bounded observation. Implementations must never affect the business outcome. */
    Observation start(Request request);

    /** Returns a side-effect-free implementation for isolated tests and optional assembly. */
    static OperationalTelemetry noop() {
        return ignored -> Observation.NOOP;
    }

    enum Type {
        OUTBOX,
        PROJECTION,
        SSE,
        INBOX,
        NOTIFICATION,
        PROVIDER,
        AGENT,
        OPERATIONS
    }

    enum Operation {
        PUBLISH,
        REPLAY,
        STREAM,
        QUERY,
        DISPATCH,
        RECONCILE,
        REDELIVER,
        SUMMARIZE
    }

    enum WorkerRole {
        API,
        WORKER,
        SCHEDULER,
        PROVIDER
    }

    enum ProviderKey {
        NONE,
        LARK,
        GITHUB,
        MODEL
    }

    enum ProjectionName {
        NONE,
        TEAM_ACTIVITY,
        MEMBER_INBOX,
        OTHER
    }

    enum StreamType {
        NONE,
        CONVERSATION,
        TASK,
        TEAM
    }

    enum Outcome {
        SUCCESS,
        RETRY,
        FAILURE,
        CANCELLED,
        REJECTED,
        DEGRADED
    }

    /** Stable error families safe for metrics, traces and structured logs. */
    enum ErrorCode {
        NONE,
        TIMEOUT,
        UNAVAILABLE,
        AUTHENTICATION,
        PERMISSION,
        RATE_LIMITED,
        INVALID_RESPONSE,
        IDENTITY_MISMATCH,
        FENCED,
        CONFLICT,
        INVALID_INPUT,
        CANCELLED,
        UNKNOWN,
        DROPPED,
        BUDGET_EXCEEDED,
        LEASE_REJECTED,
        HANDLER_MISSING,
        TRANSPORT_FAILURE,
        ACK_UNCONFIRMED,
        RETRY_EXHAUSTED,
        AUTHORIZATION_DRIFT,
        CREDENTIAL_UNAVAILABLE,
        OUTPUT_INVALID,
        INTERNAL
    }

    /** Exact low-cardinality coordinates for one operation. */
    record Request(
            Type type,
            Operation operation,
            WorkerRole workerRole,
            ProviderKey providerKey,
            ProjectionName projectionName,
            StreamType streamType) {

        public Request {
            type = Objects.requireNonNull(type, "type");
            operation = Objects.requireNonNull(operation, "operation");
            workerRole = Objects.requireNonNull(workerRole, "workerRole");
            providerKey = Objects.requireNonNull(providerKey, "providerKey");
            projectionName = Objects.requireNonNull(projectionName, "projectionName");
            streamType = Objects.requireNonNull(streamType, "streamType");
            requireDimensions(type, providerKey, projectionName, streamType);
            requireOperation(type, operation);
        }

        public static Request outbox() {
            return basic(Type.OUTBOX, Operation.PUBLISH, WorkerRole.WORKER);
        }

        public static Request projection(ProjectionName projectionName) {
            return new Request(
                    Type.PROJECTION,
                    Operation.REPLAY,
                    WorkerRole.WORKER,
                    ProviderKey.NONE,
                    Objects.requireNonNull(projectionName, "projectionName"),
                    StreamType.NONE);
        }

        public static Request sse(StreamType streamType) {
            return new Request(
                    Type.SSE,
                    Operation.STREAM,
                    WorkerRole.API,
                    ProviderKey.NONE,
                    ProjectionName.NONE,
                    Objects.requireNonNull(streamType, "streamType"));
        }

        public static Request inbox() {
            return basic(Type.INBOX, Operation.QUERY, WorkerRole.API);
        }

        public static Request notification(Operation operation) {
            if (operation != Operation.DISPATCH
                    && operation != Operation.RECONCILE
                    && operation != Operation.REDELIVER) {
                throw new IllegalArgumentException("notification operation is not allowed");
            }
            return new Request(
                    Type.NOTIFICATION,
                    operation,
                    WorkerRole.WORKER,
                    ProviderKey.LARK,
                    ProjectionName.NONE,
                    StreamType.NONE);
        }

        public static Request lark(Operation operation) {
            if (operation != Operation.QUERY && operation != Operation.DISPATCH) {
                throw new IllegalArgumentException("Lark operation is not allowed");
            }
            return new Request(
                    Type.PROVIDER,
                    operation,
                    WorkerRole.PROVIDER,
                    ProviderKey.LARK,
                    ProjectionName.NONE,
                    StreamType.NONE);
        }

        public static Request teamObserver() {
            return basic(Type.AGENT, Operation.SUMMARIZE, WorkerRole.WORKER);
        }

        private static Request basic(Type type, Operation operation, WorkerRole workerRole) {
            return new Request(
                    type,
                    operation,
                    workerRole,
                    ProviderKey.NONE,
                    ProjectionName.NONE,
                    StreamType.NONE);
        }

        private static void requireDimensions(
                Type type,
                ProviderKey provider,
                ProjectionName projection,
                StreamType stream) {
            if ((type == Type.NOTIFICATION || type == Type.PROVIDER)
                    != (provider != ProviderKey.NONE)) {
                throw new IllegalArgumentException("providerKey does not match telemetry type");
            }
            if ((type == Type.PROJECTION) != (projection != ProjectionName.NONE)) {
                throw new IllegalArgumentException("projectionName does not match telemetry type");
            }
            if ((type == Type.SSE) != (stream != StreamType.NONE)) {
                throw new IllegalArgumentException("streamType does not match telemetry type");
            }
        }

        private static void requireOperation(Type type, Operation operation) {
            boolean allowed = switch (type) {
                case OUTBOX -> operation == Operation.PUBLISH;
                case PROJECTION -> operation == Operation.REPLAY;
                case SSE -> operation == Operation.STREAM;
                case INBOX -> operation == Operation.QUERY;
                case NOTIFICATION -> operation == Operation.DISPATCH
                        || operation == Operation.RECONCILE
                        || operation == Operation.REDELIVER;
                case PROVIDER -> operation == Operation.QUERY
                        || operation == Operation.DISPATCH;
                case AGENT -> operation == Operation.SUMMARIZE;
                case OPERATIONS -> false;
            };
            if (!allowed) {
                throw new IllegalArgumentException("operation does not match telemetry type");
            }
        }
    }

    /** Idempotent completion handle; no method accepts raw failure text or an exception. */
    interface Observation {

        Observation NOOP = (outcome, errorCode) -> {};

        void complete(Outcome outcome, ErrorCode errorCode);

        default void succeed() {
            complete(Outcome.SUCCESS, ErrorCode.NONE);
        }

        default void fail(ErrorCode errorCode) {
            complete(Outcome.FAILURE, Objects.requireNonNull(errorCode, "errorCode"));
        }

        default void cancel() {
            complete(Outcome.CANCELLED, ErrorCode.CANCELLED);
        }
    }
}
