package io.crewscope.server.security.session;

/**
 * Condenses one User-Agent header into the owner-facing {@code "browser · OS"} device summary
 * stored on browser Sessions (M13b-A02). The raw header is never persisted, and the output is
 * assembled exclusively from this class's fixed label vocabulary — no header fragment can reach
 * the stored attribute, so hostile lengths and content are structurally impossible.
 *
 * <p>Ordering matters and mirrors real-world token overlap: iOS agents carry {@code like Mac OS X},
 * Android agents carry {@code Linux}, and every Chromium derivative carries {@code Chrome/}.
 * The checks below run in the order that keeps those overlaps from mislabeling the device.
 */
public final class UserAgentDeviceSummary {

    private UserAgentDeviceSummary() {}

    /**
     * Returns the {@code "browser · OS"} summary, or {@code null} when the header is absent,
     * blank, or admits no browser and no operating-system match. {@code null} means "no stored
     * attribute"; callers render their unknown-device fallback.
     */
    public static String summarize(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        String browser = browser(userAgent);
        String operatingSystem = operatingSystem(userAgent);
        if (browser == null && operatingSystem == null) {
            return null;
        }
        return (browser == null ? "未知浏览器" : browser)
                + " · " + (operatingSystem == null ? "未知系统" : operatingSystem);
    }

    private static String browser(String userAgent) {
        // Every Chromium fork also advertises Chrome/, so derivatives are matched first.
        if (userAgent.contains("Edg/") || userAgent.contains("Edge/")) {
            return "Edge";
        }
        if (userAgent.contains("SamsungBrowser/")) {
            return "Samsung Internet";
        }
        if (userAgent.contains("OPR/")) {
            return "Opera";
        }
        // iOS ports use CriOS/FxiOS instead of the desktop tokens.
        if (userAgent.contains("CriOS/") || userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("FxiOS/") || userAgent.contains("Firefox/")) {
            return "Firefox";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return null;
    }

    private static String operatingSystem(String userAgent) {
        // iOS agents carry "like Mac OS X", and Android agents carry "Linux";
        // both must be tested before their overlapping desktop tokens.
        if (userAgent.contains("iPhone") || userAgent.contains("iPad") || userAgent.contains("iPod")) {
            return "iOS";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "macOS";
        }
        if (userAgent.contains("CrOS ")) {
            return "ChromeOS";
        }
        if (userAgent.contains("Linux") || userAgent.contains("X11")) {
            return "Linux";
        }
        return null;
    }
}
