package io.crewscope.domain.notification;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Normalized HTTPS origin allowed in a fixed-template link variable. */
public record TrustedNotificationOrigin(String scheme, String host, int port) {

    public TrustedNotificationOrigin {
        scheme = requireHttps(scheme);
        host = requireHost(host);
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new DomainValidationException("trustedOrigin.port", "must be a valid TCP port");
        }
    }

    public static TrustedNotificationOrigin https(String host) {
        return new TrustedNotificationOrigin("https", host, -1);
    }

    /** Validates the complete URI and compares scheme, host and effective port exactly. */
    public boolean supports(String value) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute()
                    && uri.getRawUserInfo() == null
                    && uri.getRawFragment() == null
                    && uri.getHost() != null
                    && scheme.equalsIgnoreCase(uri.getScheme())
                    && host.equalsIgnoreCase(uri.getHost())
                    && effectivePort(port, scheme) == effectivePort(uri.getPort(), uri.getScheme());
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    private static String requireHttps(String value) {
        if (value == null || !"https".equalsIgnoreCase(value.strip())) {
            throw new DomainValidationException("trustedOrigin.scheme", "must be HTTPS");
        }
        return "https";
    }

    private static String requireHost(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("@")) {
            throw new DomainValidationException("trustedOrigin.host", "must be a DNS host");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static int effectivePort(int value, String scheme) {
        return value == -1 && "https".equalsIgnoreCase(scheme) ? 443 : value;
    }
}
