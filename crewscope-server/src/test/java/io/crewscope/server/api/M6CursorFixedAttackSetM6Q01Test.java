package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.crewscope.application.activity.ActivityFilter;
import io.crewscope.application.audit.AuditQueryFilter;
import io.crewscope.application.inbox.InboxFilter;
import io.crewscope.application.notification.NotificationDeliveryFilter;
import io.crewscope.domain.projection.ProjectionName;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Stable malformed-token attack denominator shared by all six M6 cursor boundaries. */
class M6CursorFixedAttackSetM6Q01Test {

    private static final OrganizationId ORGANIZATION_ID = OrganizationId.generate();
    private static final TeamId TEAM_ID = TeamId.generate();
    private static final TeamActivityCursorKeyRing KEYS =
            new TeamActivityCursorKeyRing("m6-q01", Map.of("m6-q01", signingKey()));

    @TestFactory
    Stream<DynamicTest> rejectsEveryMalformedCursorBeforeQueryExecution() {
        List<CursorSurface> surfaces = surfaces();
        List<MalformedToken> tokens = malformedTokens();
        if (surfaces.size() * tokens.size() != 36) {
            throw new IllegalStateException("M6-Q01 cursor attack denominator must remain 36");
        }
        return IntStream.range(0, surfaces.size() * tokens.size()).mapToObj(index -> {
            CursorSurface surface = surfaces.get(index / tokens.size());
            MalformedToken token = tokens.get(index % tokens.size());
            return dynamicTest(
                    "CU-%02d-%s-%s".formatted(index + 1, surface.name(), token.name()),
                    () -> assertInvalid(() -> surface.decode().accept(token.value())));
        });
    }

    private static List<CursorSurface> surfaces() {
        TeamActivityCursorCodec activity = new TeamActivityCursorCodec(
                KEYS, Clock.systemUTC(), Duration.ofHours(24), Duration.ofSeconds(30));
        InboxCursorCodec inbox = new InboxCursorCodec();
        AuditCursorCodec audit = new AuditCursorCodec(KEYS);
        CorrelationCursorCodec correlation = new CorrelationCursorCodec(KEYS);
        LarkMappingCursorCodec mapping = new LarkMappingCursorCodec(KEYS);
        NotificationDeliveryCursorCodec delivery = new NotificationDeliveryCursorCodec(KEYS);
        UUID correlationId = UUID.randomUUID();
        return List.of(
                new CursorSurface("activity", token -> activity.decode(
                        token,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        new ProjectionName("team-activity"),
                        ActivityFilter.ALL)),
                new CursorSurface("inbox", token -> inbox.decode(
                        token, ORGANIZATION_ID, TEAM_ID, InboxFilter.OPEN)),
                new CursorSurface("audit", token -> audit.decode(
                        token, ORGANIZATION_ID, TEAM_ID, AuditQueryFilter.ALL)),
                new CursorSurface("correlation", token -> correlation.decode(
                        token, ORGANIZATION_ID, TEAM_ID, correlationId)),
                new CursorSurface("lark-mapping", token -> mapping.decode(
                        token, ORGANIZATION_ID, TEAM_ID, Optional.empty())),
                new CursorSurface("notification-delivery", token -> delivery.decode(
                        token,
                        ORGANIZATION_ID,
                        TEAM_ID,
                        NotificationDeliveryFilter.ALL)));
    }

    private static List<MalformedToken> malformedTokens() {
        return Arrays.asList(
                new MalformedToken("null", null),
                new MalformedToken("blank", "   "),
                new MalformedToken("illegal-character", "cursor!*"),
                new MalformedToken("overlong", "A".repeat(2_048)),
                new MalformedToken("non-canonical-padding", "QQ=="),
                new MalformedToken("truncated", "A"));
    }

    private static String signingKey() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (31 + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        ApiRequestException failure = assertThrows(ApiRequestException.class, executable);
        assertEquals("invalid_cursor", failure.code());
    }

    private record CursorSurface(String name, Consumer<String> decode) {}

    private record MalformedToken(String name, String value) {}
}
