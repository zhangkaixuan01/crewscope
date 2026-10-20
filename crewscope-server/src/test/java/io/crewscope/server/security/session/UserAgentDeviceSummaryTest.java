package io.crewscope.server.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Matrix for the User-Agent condenser behind the session device summary (M13b-A02). Real-world
 * token overlap is the hazard this pins: iOS agents carry {@code like Mac OS X}, Android agents
 * carry {@code Linux}, and every Chromium derivative carries {@code Chrome/}.
 */
class UserAgentDeviceSummaryTest {

    @Test
    void condensesDesktopAgentsToBrowserAndOs() {
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo("Chrome · Windows");
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"))
                .isEqualTo("Safari · macOS");
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0"))
                .isEqualTo("Firefox · Linux");
    }

    @Test
    void chromiumDerivativesWinOverTheSharedChromeToken() {
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"))
                .isEqualTo("Edge · Windows");
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.0.0 Safari/537.36 OPR/112.0.0.0"))
                .isEqualTo("Opera · Windows");
    }

    @Test
    void iosAndAndroidAgentsBeatTheirOverlappingOsTokens() {
        // "like Mac OS X" must not reach the macOS branch.
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Safari · iOS");
        // iOS Chrome advertises CriOS, and "Linux" must not relabel Android.
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) CriOS/126.0.6478.114 Mobile/15E148 Safari/604.1"))
                .isEqualTo("Chrome · iOS");
        assertThat(UserAgentDeviceSummary.summarize(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.6478.72 Mobile Safari/537.36"))
                .isEqualTo("Chrome · Android");
    }

    @Test
    void partialRecognitionKeepsTheKnownHalf() {
        assertThat(UserAgentDeviceSummary.summarize("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"))
                .isEqualTo("未知浏览器 · Windows");
        assertThat(UserAgentDeviceSummary.summarize("Chrome/126.0.0.0"))
                .isEqualTo("Chrome · 未知系统");
    }

    @Test
    void absentOrUnrecognizableHeadersYieldNullForTheUnknownFallback() {
        assertThat(UserAgentDeviceSummary.summarize(null)).isNull();
        assertThat(UserAgentDeviceSummary.summarize("  ")).isNull();
        assertThat(UserAgentDeviceSummary.summarize("curl/8.4.0")).isNull();
        // Internal non-browser clients such as WebTestClient's default agent stay null too.
        assertThat(UserAgentDeviceSummary.summarize("ReactorNetty/1.1.22")).isNull();
    }

    @Test
    void hostileHeadersCannotInjectAnythingIntoTheStoredSummary() {
        // The summary is assembled only from the fixed label vocabulary, so no header
        // fragment — length or content — can survive into the stored attribute.
        String hostile = "Mozilla/5.0 (Windows NT 999" + "x".repeat(500)
                + "; <script>alert(1)</script>) Chrome/126.0.0.0";
        assertThat(UserAgentDeviceSummary.summarize(hostile)).isEqualTo("Chrome · Windows");
    }
}
