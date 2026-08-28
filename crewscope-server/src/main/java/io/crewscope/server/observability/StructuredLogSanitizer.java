package io.crewscope.server.observability;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies the shared field-level redaction and log-injection boundary. */
public final class StructuredLogSanitizer {

    public static final String REDACTED = "[REDACTED]";
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(bearer\\s+\\S+"
                    + "|\\b(?:sk|ghp|github_pat)[-_][a-z0-9_\\-]{8,}"
                    + "|\\b[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}\\b"
                    + "|\\b1[3-9]\\d{9}\\b"
                    + "|(?:password|passwd|secret|access[_-]?token|refresh[_-]?token|api[_-]?key)"
                    + "\\s*[:=]\\s*[^\\s,;]+)");

    private static final Set<String> EXACT_SENSITIVE_FIELDS = Set.of(
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "password",
            "passwd",
            "secret",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "apikey",
            "privatekey",
            "keymaterial",
            "credential",
            "credentials",
            "username",
            "loginidentifier",
            "networkaddress",
            "sessionid",
            "passwordhash",
            "ciphertext",
            "nonce",
            "authenticationtag",
            "gcmtag",
            "prompt",
            "systemprompt",
            "prompttemplate",
            "reasoning",
            "thinking",
            "toolinput",
            "toolarguments",
            "toolresult",
            "tooloutput",
            "agentstate",
            "agentstatesnapshot",
            "statesnapshot",
            "runtimecontext",
            "tasktokenclaims",
            "claimtokenhash",
            "providerrequest",
            "providerresponse",
            "providerpayload",
            "rawprovidererror",
            "email",
            "emailaddress",
            "phone",
            "phonenumber",
            "mobile",
            "mobilephone",
            "displayname",
            "fullname",
            "openid",
            "unionid",
            "stacktrace",
            "exception",
            "exceptionmessage",
            "throwable");

    private StructuredLogSanitizer() {}

    /** Redacts sensitive fields and normalizes control characters in safe values. */
    public static String sanitize(String fieldName, Object value) {
        if (isSensitiveField(fieldName)) {
            return REDACTED;
        }
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder();
        value.toString().codePoints().forEach(codePoint -> safe.appendCodePoint(
                isLogSeparator(codePoint) ? ' ' : codePoint));
        String normalized = safe.toString().strip();
        if (SENSITIVE_VALUE.matcher(normalized).find()) {
            return REDACTED;
        }
        if (normalized.length() <= MAX_VALUE_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_VALUE_LENGTH - 1) + "…";
    }

    /** Returns true for case- and separator-insensitive credential-bearing field names. */
    public static boolean isSensitiveField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String canonical = fieldName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return EXACT_SENSITIVE_FIELDS.contains(canonical)
                || canonical.endsWith("password")
                || canonical.endsWith("secret")
                || canonical.endsWith("token")
                || canonical.endsWith("apikey")
                || canonical.endsWith("privatekey")
                || canonical.endsWith("keymaterial")
                || canonical.endsWith("credential")
                || canonical.endsWith("ciphertext")
                || canonical.endsWith("prompt")
                || canonical.endsWith("reasoning")
                || canonical.endsWith("thinking")
                || isToolContentField(canonical);
    }

    private static boolean isToolContentField(String canonical) {
        return canonical.contains("tool")
                && (canonical.endsWith("input")
                        || canonical.endsWith("arguments")
                        || canonical.endsWith("args")
                        || canonical.endsWith("result")
                        || canonical.endsWith("output"));
    }

    private static boolean isLogSeparator(int codePoint) {
        return Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029;
    }
}
