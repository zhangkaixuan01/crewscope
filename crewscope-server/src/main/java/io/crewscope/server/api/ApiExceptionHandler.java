package io.crewscope.server.api;

import io.crewscope.application.error.ApplicationErrorMapper;
import io.crewscope.application.execution.PlatformExecutionContextResolutionException;
import io.crewscope.application.conversation.ConversationEventCursorExpiredException;
import io.crewscope.application.task.TaskEventCursorExpiredException;
import io.crewscope.domain.shared.error.DomainError;
import io.crewscope.domain.shared.error.DomainErrorCategory;
import io.crewscope.server.observability.ApiObservabilityContext;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
