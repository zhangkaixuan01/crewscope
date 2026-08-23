package io.crewscope.domain.model;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.net.URI;
import java.net.URISyntaxException;

/** Trusted HTTP(S) base endpoint stored in a platform model provider definition. */
public record ModelEndpoint(String value) {

    public static final int MAX_LENGTH = 2_048;

    public ModelEndpoint {
        if (value == null || value.isBlank() || value.strip().length() > MAX_LENGTH) {
            throw new DomainValidationException(
                    "modelProvider.defaultEndpoint", "must be a non-blank HTTP(S) endpoint");
        }
        value = value.strip();
        try {
            URI endpoint = new URI(value);
            boolean supportedScheme = "https".equalsIgnoreCase(endpoint.getScheme())
                    || "http".equalsIgnoreCase(endpoint.getScheme());
            if (!endpoint.isAbsolute()
                    || !supportedScheme
                    || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null
                    || endpoint.getFragment() != null) {
                throw invalidEndpoint();
            }
        } catch (URISyntaxException invalid) {
            throw invalidEndpoint();
        }
    }

    private static DomainValidationException invalidEndpoint() {
        return new DomainValidationException(
                "modelProvider.defaultEndpoint",
                "must be an absolute HTTP(S) endpoint without credentials, query or fragment");
    }

    @Override
    public String toString() {
        return value;
    }
}
