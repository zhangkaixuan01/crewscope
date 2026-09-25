package io.crewscope.server.api;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Shared `after`/`limit` validation for cursor-paginated list endpoints. */
public final class ApiPagination {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int DELIVERY_CARDS_DEFAULT_LIMIT = 20;
    public static final int DELIVERY_CARDS_MAX_LIMIT = 50;
    public static final int WORK_ITEM_DEFAULT_LIMIT = 20;
    public static final int WORK_ITEM_MAX_LIMIT = 100;
    public static final int DIRECTORY_DEFAULT_LIMIT = 20;
    public static final int DIRECTORY_MAX_LIMIT = 200;
    public static final int WORK_DESK_SECTION_DEFAULT_LIMIT = 20;
    public static final int WORK_DESK_SECTION_MAX_LIMIT = 100;

    private ApiPagination() {}

    public static int limit(Integer requestedLimit) {
        return boundedLimit(requestedLimit, DEFAULT_LIMIT, MAX_LIMIT);
    }

    /** WorkItem list pages (M9b-A06 D05): every row carries the summary read model. */
    public static int workItemLimit(Integer requestedLimit) {
        return boundedLimit(requestedLimit, WORK_ITEM_DEFAULT_LIMIT, WORK_ITEM_MAX_LIMIT);
    }

    /** Uses a smaller budget while delivery cards still enrich Review and Action per Task. */
    public static int deliveryCardsLimit(Integer requestedLimit) {
        return boundedLimit(
                requestedLimit, DELIVERY_CARDS_DEFAULT_LIMIT, DELIVERY_CARDS_MAX_LIMIT);
    }

    /** Uses the larger bounded page allowed by the subject directory contract. */
    public static int directoryLimit(Integer requestedLimit) {
        return boundedLimit(requestedLimit, DIRECTORY_DEFAULT_LIMIT, DIRECTORY_MAX_LIMIT);
    }

    /** One WorkDesk section's own page (M9b-A06): the first screen and each continuation share it. */
    public static int workDeskSectionLimit(Integer requestedLimit) {
        return boundedLimit(
                requestedLimit, WORK_DESK_SECTION_DEFAULT_LIMIT, WORK_DESK_SECTION_MAX_LIMIT);
    }

    private static int boundedLimit(Integer requestedLimit, int defaultLimit, int maxLimit) {
        if (requestedLimit == null) {
            return defaultLimit;
        }
        if (requestedLimit < 1 || requestedLimit > maxLimit) {
            throw new ApiRequestException(
                    HttpStatus.BAD_REQUEST,
                    "invalid_request",
                    "limit must be between 1 and " + maxLimit,
                    Map.of("parameter", "limit"));
        }
        return requestedLimit;
    }
}
