package io.crewscope.integration.provider.collaboration;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;

/** Exact production origin with an explicit literal-loopback test escape hatch. */
public final class LarkEndpointPolicy {

    public static final URI PRODUCTION_ORIGIN = URI.create("https://open.feishu.cn");

    private LarkEndpointPolicy() {}

    public static URI requireAllowed(URI endpoint, boolean loopbackEnabled) {
        URI required = Objects.requireNonNull(endpoint, "endpoint").normalize();
        boolean exactProduction = "https".equals(required.getScheme())
                && "open.feishu.cn".equals(required.getHost())
                && required.getPort() == -1;
        boolean exactLoopback = loopbackEnabled
                && "http".equals(required.getScheme())
                && isLiteralLoopback(required.getHost())
                && required.getPort() > 0;
        boolean cleanOrigin = required.getUserInfo() == null
                && required.getQuery() == null
                && required.getFragment() == null
                && (required.getPath().isEmpty() || "/".equals(required.getPath()));
        if ((!exactProduction && !exactLoopback) || !cleanOrigin) {
            throw new IllegalArgumentException("Lark API origin is not on the fixed allowlist");
        }
        String host = required.getHost().contains(":")
                ? '[' + required.getHost() + ']'
                : required.getHost();
        return URI.create(required.getScheme() + "://" + host
                + (required.getPort() < 0 ? "" : ":" + required.getPort()));
    }

    private static boolean isLiteralLoopback(String host) {
        if (!("127.0.0.1".equals(host) || "::1".equals(host))) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException ignored) {
            return false;
        }
    }
}
