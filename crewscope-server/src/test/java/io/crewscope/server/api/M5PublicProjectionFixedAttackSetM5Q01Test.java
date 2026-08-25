package io.crewscope.server.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import io.crewscope.application.action.ActionBundleView;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Stable M5-Q01 public DTO leakage probes for Model, Agent, Review and Delivery surfaces. */
class M5PublicProjectionFixedAttackSetM5Q01Test {

    private static final Set<String> FORBIDDEN_COMPONENT_FRAGMENTS = Set.of(
            "apikey",
            "secret",
            "credentialid",
            "credentialreference",
            "ciphertext",
            "plaintext",
            "authorization",
            "bearer",
            "accesstoken",
            "refreshtoken",
            "installationtoken",
            "endpoint",
            "baseurl",
            "rawbody",
            "rawpayload",
            "providerbody",
            "storageuri",
            "hostpath",
            "systemprompt",
            "toolarguments",
            "toolresult",
            "reasoning",
            "statesnapshot",
            "leaseid",
            "claimtoken",
            "fencingtoken");

    /** Each response shape is an independent fixed probe so the denominator cannot drift silently. */
    @TestFactory
    Stream<DynamicTest> blocksSensitiveFieldsAcrossEveryM5PublicProjection() {
        List<Class<?>> projections = List.of(
                ModelConnectionController.ConnectionResponse.class,
                AgentManagementController.TemplateResponse.class,
                AgentManagementController.AgentResponse.class,
                AgentManagementController.ConfigurationResponse.class,
                AgentManagementController.BindingResponse.class,
                AgentManagementController.ModelSelectionResponse.class,
                AgentConfigurationController.CurrentConfigurationResponse.class,
                AgentConfigurationController.SelectableModelResponse.class,
                AgentConfigurationController.PreflightResponse.class,
                AgentConfigurationController.ResolvedSelectionResponse.class,
                ReviewController.ReviewResponse.class,
                ReviewController.FindingResponse.class,
                ReviewController.DecisionResponse.class,
                ReviewController.ReviewerExecutionResponse.class,
                GitHubConnectionController.ConnectionResponse.class,
                GitHubConnectionController.BindingResponse.class,
                GitHubConnectionController.RepositoryResponse.class,
                GitHubConnectionController.RemotePreflightResponse.class,
                ActionBundleView.class,
                ActionBundleView.ConfirmationView.class,
                ActionBundleView.PlannedActionView.class,
                ActionBundleView.ActionParameterView.class,
                ActionBundleView.DispatchView.class,
                ActionBundleView.ActionReceiptView.class,
                ActionBundleView.ExternalResultView.class);
        return IntStream.range(0, projections.size()).mapToObj(index -> {
            Class<?> projection = projections.get(index);
            return dynamicTest(
                    "LK-%02d-%s".formatted(index + 1, projection.getSimpleName()),
                    () -> assertPublicProjection(projection));
        });
    }

    private static void assertPublicProjection(Class<?> projection) {
        assertTrue(projection.isRecord(), () -> projection.getName() + " must remain a record");
        List<String> leaked = Arrays.stream(projection.getRecordComponents())
                .map(RecordComponent::getName)
                .map(M5PublicProjectionFixedAttackSetM5Q01Test::normalize)
                .filter(M5PublicProjectionFixedAttackSetM5Q01Test::isForbidden)
                .toList();
        assertTrue(leaked.isEmpty(), () -> projection.getName()
                + " exposes forbidden public components " + leaked);
    }

    private static String normalize(String value) {
        return value.replace("_", "").replace("-", "").toLowerCase();
    }

    private static boolean isForbidden(String value) {
        return FORBIDDEN_COMPONENT_FRAGMENTS.stream().anyMatch(value::contains);
    }
}
