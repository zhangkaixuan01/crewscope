package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.crewscope.application.collaboration.LarkMemberMappingCursor;
import io.crewscope.application.notification.NotificationDeliveryCursor;
import io.crewscope.application.collaboration.LarkConnectionView;
import io.crewscope.application.notification.NotificationDeliveryView;
import io.crewscope.application.notification.NotificationDeliveryFilter;
import io.crewscope.domain.collaboration.LarkMemberMappingId;
import io.crewscope.domain.collaboration.LarkMemberMappingStatus;
import io.crewscope.domain.inbox.InboxItemType;
import io.crewscope.domain.notification.NotificationDeliveryId;
import io.crewscope.domain.notification.NotificationDeliveryStatus;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.team.TeamMemberId;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Signed scope/filter/rotation contract for M6-A04 administration keyset Cursors. */
class LarkAdministrationCursorM6A04Test {

    private static final OrganizationId ORGANIZATION = OrganizationId.generate();
    private static final TeamId TEAM = TeamId.generate();
    private static final String KEY_1 = key(17);
    private static final String KEY_2 = key(71);

    @Test
    void mappingCursorRoundTripsAndRejectsScopeFilterAndTampering() {
        LarkMemberMappingCursor cursor = new LarkMemberMappingCursor(
                UtcTimestamp.parse("2026-08-27T04:00:00.123Z"),
                LarkMemberMappingId.generate());
        LarkMappingCursorCodec codec = new LarkMappingCursorCodec(keys("k1", KEY_1));
        String token = codec.encode(
                cursor, ORGANIZATION, TEAM, Optional.of(LarkMemberMappingStatus.ACTIVE));

        assertEquals(cursor, codec.decode(
                token, ORGANIZATION, TEAM, Optional.of(LarkMemberMappingStatus.ACTIVE)));
        assertInvalid(() -> codec.decode(
                token, ORGANIZATION, TeamId.generate(), Optional.of(LarkMemberMappingStatus.ACTIVE)));
        assertInvalid(() -> codec.decode(
                token, ORGANIZATION, TEAM, Optional.of(LarkMemberMappingStatus.REVOKED)));
        assertInvalid(() -> codec.decode(tamper(token), ORGANIZATION, TEAM,
                Optional.of(LarkMemberMappingStatus.ACTIVE)));
    }

    @Test
    void deliveryCursorBindsAllFiltersAndSupportsKeyRotation() {
        NotificationDeliveryCursor cursor = new NotificationDeliveryCursor(
                UtcTimestamp.parse("2026-08-27T04:01:00Z"),
                new NotificationDeliveryId(java.util.UUID.randomUUID()));
        NotificationDeliveryFilter filter = new NotificationDeliveryFilter(
                Set.of(NotificationDeliveryStatus.FAILED_FINAL),
                Set.of(InboxItemType.EXCEPTION),
                Optional.of(TeamMemberId.generate()));
        NotificationDeliveryCursorCodec oldCodec =
                new NotificationDeliveryCursorCodec(keys("k1", KEY_1));
        String token = oldCodec.encode(cursor, ORGANIZATION, TEAM, filter);

        TeamActivityCursorKeyRing rotating = new TeamActivityCursorKeyRing(
                "k2", Map.of("k1", KEY_1, "k2", KEY_2));
        assertEquals(cursor, new NotificationDeliveryCursorCodec(rotating)
                .decode(token, ORGANIZATION, TEAM, filter));
        assertInvalid(() -> new NotificationDeliveryCursorCodec(keys("k2", KEY_2))
                .decode(token, ORGANIZATION, TEAM, filter));
        assertInvalid(() -> oldCodec.decode(
                token, ORGANIZATION, TEAM, NotificationDeliveryFilter.ALL));
    }

    @Test
    void publicDtosDoNotDeclareCredentialIdentityOrProviderPayloadFields() {
        Set<String> forbidden = Set.of(
                "appSecret", "credentialId", "grantId", "tenantKey", "openId", "unionId",
                "variables", "authorizationSnapshot", "providerMessageId", "endpoint",
                "requestBody", "responseBody", "claimToken", "leaseToken");

        for (Class<?> type : List.of(
                LarkConnectionView.class,
                LarkAdministrationController.MappingResponse.class,
                NotificationDeliveryView.class)) {
            Set<String> fields = Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(java.util.stream.Collectors.toSet());
            assertFalse(fields.stream().anyMatch(forbidden::contains), type.getSimpleName());
        }
    }

    private static TeamActivityCursorKeyRing keys(String id, String key) {
        return new TeamActivityCursorKeyRing(id, Map.of(id, key));
    }

    private static String key(int seed) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) value[index] = (byte) (seed + index);
        return Base64.getEncoder().encodeToString(value);
    }

    private static String tamper(String token) {
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        return token.substring(0, token.length() - 1) + replacement;
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable executable) {
        assertEquals("invalid_cursor", assertThrows(ApiRequestException.class, executable).code());
    }
}
