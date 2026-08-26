package io.crewscope.server.api;

import io.crewscope.application.activity.TeamActivityCursorExpiredException;
import io.crewscope.application.coding.CodingArtifactRangeNotSatisfiableException;
import io.crewscope.application.coding.RepositoryBindingPreflightException;
import io.crewscope.application.coding.RepositoryCatalogUnavailableException;
import io.crewscope.application.conversation.ConversationEventCursorExpiredException;
import io.crewscope.application.error.ApplicationErrorMapper;
import io.crewscope.application.execution.PlatformExecutionContextResolutionException;
import io.crewscope.application.github.GitHubProviderException;
import io.crewscope.application.model.ModelConnectionCredentialException;
import io.crewscope.application.runtime.CodingRuntimeOperationsUnavailableException;
import io.crewscope.application.task.TaskEventCursorExpiredException;
import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCategory;
import io.crewscope.infrastructure.workspace.repository.CodingArtifactException;
import io.crewscope.server.observability.ApiObservabilityContext;
import jakarta.validation.ConstraintViolationException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Maps transport, validation, domain and unknown failures to the safe `/api/v1` envelope. */
@RestControllerAdvice(basePackages = "io.crewscope.server")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorResponse> handle(
            Throwable failure, ServerWebExchange exchange) {
        UUID correlationId = ApiCorrelationIds.resolve(exchange);
        if (failure instanceof ApiRequestException apiFailure) {
            return response(
                    apiFailure.status(),
                    apiFailure.code(),
                    apiFailure.getMessage(),
                    false,
                    null,
                    apiFailure.details(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof ConversationEventCursorExpiredException) {
            return response(
                    HttpStatus.GONE,
                    "cursor_expired",
                    "The Conversation Event cursor is no longer retained",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof TaskEventCursorExpiredException) {
            return response(
                    HttpStatus.GONE,
                    "cursor_expired",
                    "The Task Event cursor is no longer retained",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof TeamActivityCursorExpiredException) {
            return response(
                    HttpStatus.GONE,
                    "cursor_expired",
                    "The Team Activity cursor is no longer retained",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof PlatformExecutionContextResolutionException) {
            return response(
                    HttpStatus.FORBIDDEN,
                    "execution_context_unavailable",
                    "The Personal Agent cannot be invoked with the current authorization facts",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof ModelConnectionCredentialException credentialFailure) {
            HttpStatus status = switch (credentialFailure.error()) {
                case CONNECTION_NOT_FOUND, PROVIDER_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case CREDENTIAL_MISMATCH -> HttpStatus.CONFLICT;
                case CREDENTIAL_NOT_FOUND, CREDENTIAL_UNAVAILABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            };
            return response(
                    status,
                    "model_connection_" + credentialFailure.error().name().toLowerCase(Locale.ROOT),
                    credentialFailure.getMessage(),
                    false,
                    null,
                    Map.of("reason", credentialFailure.error().name()),
                    correlationId,
                    exchange);
        }
        if (failure instanceof GitHubProviderException githubFailure) {
            HttpStatus status = switch (githubFailure.code()) {
                case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
                case PERMISSION_DENIED -> HttpStatus.FORBIDDEN;
                case RESOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
                case CONFLICT, IDENTITY_MISMATCH, DEFAULT_BRANCH_MISMATCH -> HttpStatus.CONFLICT;
                case VALIDATION_FAILED, REPOSITORY_BLOCKED, REPOSITORY_STALE,
                        CONNECTION_UNAVAILABLE, GRANT_UNAVAILABLE, CREDENTIAL_UNAVAILABLE ->
                        HttpStatus.UNPROCESSABLE_CONTENT;
                case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            boolean retryable = githubFailure.code()
                    == io.crewscope.application.github.GitHubProviderErrorCode.RATE_LIMITED
                    || githubFailure.code()
                    == io.crewscope.application.github.GitHubProviderErrorCode.PROVIDER_UNAVAILABLE;
            return response(
                    status,
                    "github_" + githubFailure.code().name().toLowerCase(Locale.ROOT),
                    githubFailure.getMessage(),
                    retryable,
                    null,
                    Map.of("reason", githubFailure.code().name()),
                    correlationId,
                    exchange);
        }
        if (failure instanceof CodingRuntimeOperationsUnavailableException) {
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "runtime_operations_unavailable",
                    "Coding Runtime operations are unavailable for the requested environment",
                    true,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof RepositoryBindingPreflightException preflightFailure) {
            HttpStatus status = preflightFailure.retryable()
                    ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.UNPROCESSABLE_CONTENT;
            return response(
                    status,
                    "repository_preflight_"
                            + preflightFailure.error().name().toLowerCase(Locale.ROOT),
                    preflightFailure.getMessage(),
                    preflightFailure.retryable(),
                    null,
                    Map.of("reason", preflightFailure.error().name()),
                    correlationId,
                    exchange);
        }
        if (failure instanceof RepositoryCatalogUnavailableException) {
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "repository_catalog_unavailable",
                    "Repository Catalog is unavailable on this server",
                    true,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof CodingArtifactRangeNotSatisfiableException rangeFailure) {
            return rangeResponse(rangeFailure.totalSize(), correlationId, exchange);
        }
        if (failure instanceof CodingArtifactException artifactFailure) {
            return codingArtifactResponse(artifactFailure, correlationId, exchange);
        }
        if (failure instanceof WebExchangeBindException bindingFailure) {
            Map<String, String> details = new TreeMap<>();
            for (FieldError fieldError : bindingFailure.getFieldErrors()) {
                details.putIfAbsent(
                        fieldError.getField(),
                        fieldError.getCode() == null ? "invalid" : fieldError.getCode());
            }
            return response(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request validation failed",
                    false,
                    null,
                    details,
                    correlationId,
                    exchange);
        }
        if (failure instanceof ServerWebInputException) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request could not be decoded",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        if (failure instanceof ConstraintViolationException
                || failure instanceof HandlerMethodValidationException) {
            return response(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "Request validation failed",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        }
        var domainError = ApplicationErrorMapper.from(failure);
        if (domainError.isPresent()) {
            if (isWorkerRoute(exchange)
                    && (domainError.orElseThrow().code()
                                    == io.crewscope.domain.shared.error.DomainErrorCode.INVALID_VALUE
                            || domainError.orElseThrow().code()
                                    == io.crewscope.domain.shared.error.DomainErrorCode.AGGREGATE_NOT_FOUND)) {
                return response(
                        HttpStatus.CONFLICT,
                        "worker_ownership_invalid",
                        "Worker command does not match the current execution owner",
                        false,
                        null,
                        Map.of(),
                        correlationId,
                        exchange);
            }
            return domainResponse(domainError.orElseThrow(), correlationId, exchange);
        }
        ApiObservabilityContext.failureType(exchange, failure.getClass());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal_error",
                "The request could not be completed",
                true,
                null,
                Map.of(),
                correlationId,
                exchange);
    }

    private ResponseEntity<ApiErrorResponse> domainResponse(
            DomainError error, UUID correlationId, ServerWebExchange exchange) {
        HttpStatus status = switch (error.category()) {
            case VALIDATION -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case POLICY -> HttpStatus.FORBIDDEN;
        };
        Long currentVersion = error.category() == DomainErrorCategory.CONFLICT
                ? parseVersion(error.details().get("actualVersion"))
                : null;
        return response(
                status,
                error.code().value(),
                error.message(),
                false,
                currentVersion,
                error.details(),
                correlationId,
                exchange);
    }

    private ResponseEntity<ApiErrorResponse> codingArtifactResponse(
            CodingArtifactException failure,
            UUID correlationId,
            ServerWebExchange exchange) {
        return switch (failure.error()) {
            case RANGE_NOT_SATISFIABLE -> failure.totalSize().isPresent()
                    ? rangeResponse(failure.totalSize().getAsLong(), correlationId, exchange)
                    : response(
                            HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                            "coding_artifact_range_not_satisfiable",
                            "The requested Coding Artifact range is not available",
                            false,
                            null,
                            Map.of(),
                            correlationId,
                            exchange);
            case SIZE_LIMIT_EXCEEDED -> response(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "coding_artifact_response_too_large",
                    "The Coding Artifact response exceeds the configured byte limit",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
            case TOO_MANY_CONCURRENT_READS -> withHeader(
                    response(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "coding_artifact_download_busy",
                            "Coding Artifact download capacity is temporarily exhausted",
                            true,
                            null,
                            Map.of(),
                            correlationId,
                            exchange),
                    HttpHeaders.RETRY_AFTER,
                    "1");
            case INVALID_CONTEXT, CONTENT_UNAVAILABLE -> response(
                    HttpStatus.NOT_FOUND,
                    "coding_artifact_unavailable",
                    "The Coding Artifact is unavailable",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
            case METADATA_MISMATCH -> response(
                    HttpStatus.CONFLICT,
                    "coding_artifact_integrity_mismatch",
                    "The Coding Artifact metadata failed integrity verification",
                    false,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
            case PUBLICATION_FAILED, LIFECYCLE_FAILED -> response(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "coding_artifact_operation_failed",
                    "The Coding Artifact operation could not be completed",
                    true,
                    null,
                    Map.of(),
                    correlationId,
                    exchange);
        };
    }

    private ResponseEntity<ApiErrorResponse> rangeResponse(
            long totalSize, UUID correlationId, ServerWebExchange exchange) {
        return withHeader(
                response(
                        HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                        "coding_artifact_range_not_satisfiable",
                        "The requested Coding Artifact range is not available",
                        false,
                        null,
                        Map.of("totalSize", Long.toString(totalSize)),
                        correlationId,
                        exchange),
                HttpHeaders.CONTENT_RANGE,
                "bytes */" + totalSize);
    }

    private static ResponseEntity<ApiErrorResponse> withHeader(
            ResponseEntity<ApiErrorResponse> response, String name, String value) {
        return ResponseEntity.status(response.getStatusCode())
                .headers(headers -> headers.putAll(response.getHeaders()))
                .header(name, value)
                .body(response.getBody());
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            boolean retryable,
            Long currentVersion,
            Map<String, String> details,
            UUID correlationId,
            ServerWebExchange exchange) {
        ApiObservabilityContext.errorCode(exchange, code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        code,
                        message,
                        correlationId,
                        retryable,
                        currentVersion,
                        details));
    }

    private static Long parseVersion(String value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isWorkerRoute(ServerWebExchange exchange) {
        return exchange.getRequest().getPath().value().startsWith("/api/internal/v1/worker/");
    }
}
