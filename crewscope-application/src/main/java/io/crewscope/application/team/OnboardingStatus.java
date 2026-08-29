package io.crewscope.application.team;

/** Current onboarding projection without exposing another account or inactive Team. */
public record OnboardingStatus(OnboardingState state, int activeTeamCount) {

    public OnboardingStatus {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (activeTeamCount < 0) {
            throw new IllegalArgumentException("activeTeamCount must not be negative");
        }
        if ((state == OnboardingState.TEAM_REQUIRED) != (activeTeamCount == 0)) {
            throw new IllegalArgumentException("state must match activeTeamCount");
        }
    }

    public static OnboardingStatus fromActiveTeamCount(int count) {
        return new OnboardingStatus(
                count == 0 ? OnboardingState.TEAM_REQUIRED : OnboardingState.COMPLETE, count);
    }

    public boolean onboardingRequired() {
        return state == OnboardingState.TEAM_REQUIRED;
    }
}
