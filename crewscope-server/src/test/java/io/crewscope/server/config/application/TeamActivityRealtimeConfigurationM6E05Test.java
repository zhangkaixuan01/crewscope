package io.crewscope.server.config.application;

import static org.mockito.Mockito.mock;

import io.crewscope.application.activity.TeamRealtimeEventStore;
import io.crewscope.server.api.TeamActivityCursorCodec;
import io.crewscope.server.api.TeamActivityCursorKeyRing;
import io.crewscope.server.api.TeamActivityRealtimeStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Fail-closed Spring assembly contract for the M6-E05 Team Activity realtime engine. */
class TeamActivityRealtimeConfigurationM6E05Test {

  private static final String VALID_KEY =
      "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(TeamActivityRealtimeConfiguration.class);

  @Test
  void keepsRealtimeBeansDisabledWhenTheFeatureSwitchIsFalse() {
    runner.withPropertyValues("crewscope.team-activity-realtime.enabled=false").run(
        context ->
            context
                .assertThat()
                .hasNotFailed()
                .doesNotHaveBean(TeamActivityCursorKeyRing.class)
                .doesNotHaveBean(TeamActivityCursorCodec.class)
                .doesNotHaveBean(TeamActivityRealtimeStream.class));
  }

  @Test
  void failsClosedWhenEnabledWithoutTheDurableStore() {
    enabledRunner().run(context -> context.assertThat().hasFailed());
  }

  @Test
  void wiresRealtimeBeansWhenEnabledWithTheDurableStore() {
    enabledRunner()
        .withBean(TeamRealtimeEventStore.class, () -> mock(TeamRealtimeEventStore.class))
        .run(
            context ->
                context
                    .assertThat()
                    .hasNotFailed()
                    .hasSingleBean(TeamActivityCursorKeyRing.class)
                    .hasSingleBean(TeamActivityCursorCodec.class)
                    .hasSingleBean(TeamActivityRealtimeStream.class));
  }

  @Test
  void rejectsAWeakCursorSigningKeyDuringStartup() {
    enabledRunner("dG9vLXNob3J0")
        .withBean(TeamRealtimeEventStore.class, () -> mock(TeamRealtimeEventStore.class))
        .run(context -> context.assertThat().hasFailed());
  }

  private ApplicationContextRunner enabledRunner() {
    return enabledRunner(VALID_KEY);
  }

  private ApplicationContextRunner enabledRunner(String encodedKey) {
    return runner.withPropertyValues(
        "crewscope.team-activity-realtime.enabled=true",
        "crewscope.team-activity-realtime.current-key-id=current",
        "crewscope.team-activity-realtime.keys.current=" + encodedKey);
  }
}
