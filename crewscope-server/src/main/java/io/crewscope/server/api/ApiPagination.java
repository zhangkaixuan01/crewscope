package io.crewscope.server.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Shared `after`/`limit` validation for cursor-paginated list endpoints. */
public final class ApiPagination {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private ApiPagination() {}

    public static int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "limit must be between 1 and 100",
                    Map.of("parameter", "limit"));
        }
        return requestedLimit;
    }
}
