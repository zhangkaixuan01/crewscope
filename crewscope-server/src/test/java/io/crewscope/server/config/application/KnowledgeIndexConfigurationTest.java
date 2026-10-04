package io.crewscope.server.config.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.crewscope.application.coding.RepositoryBindingRepository;
import io.crewscope.application.embedding.TeamEmbeddingService;
import io.crewscope.application.knowledge.KnowledgeRepository;
import io.crewscope.application.observability.OperationalTelemetry;
import io.crewscope.application.retrieval.KnowledgeEmbeddingVectorStore;
import io.crewscope.application.retrieval.KnowledgeIndexControlService;
import io.crewscope.application.retrieval.KnowledgeIndexJobService;
import io.crewscope.application.retrieval.KnowledgeIndexStatusCatalog;
import io.crewscope.application.retrieval.KnowledgeIndexWorker;
import io.crewscope.application.retrieval.KnowledgeIndexWorkerRunResult;
import io.crewscope.application.retrieval.RepositoryChunkVectorStore;
import io.crewscope.application.team.MemberRoleRepository;
import io.crewscope.application.team.TeamMembershipQuery;
import io.crewscope.application.team.TeamRepository;
import io.crewscope.application.team.TeamRoleRepository;
import io.crewscope.application.transaction.TransactionExecutor;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.infrastructure.workspace.git.GitCommandExecutor;
import io.crewscope.infrastructure.workspace.repository.ManagedRepositoryResolver;
import io.crewscope.server.observability.KnowledgeIndexHealthIndicator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Assembly contract for the durable knowledge index (M10-I01b/I01c): the switch matrix's
 * one illegal combination (index without vector) rejects refresh enqueues and reports
 * DOWN, the leased worker and its scheduler assemble only for worker profiles behind
 * both switches, the control plane assembles behind no switch at all, and the polling
 * loop never overlaps itself.
 */
class KnowledgeIndexConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KnowledgeIndexConfiguration.class)
            .withBean(
                    io.crewscope.application.retrieval.KnowledgeIndexJobRepository.class,
                    () -> mock(io.crewscope.application.retrieval.KnowledgeIndexJobRepository.class))
            .withBean(TeamEmbeddingService.class, () -> mock(TeamEmbeddingService.class))
            .withBean(KnowledgeRepository.class, () -> mock(KnowledgeRepository.class))
            .withBean(TimeProvider.class, () -> () -> null)
            .withBean(ObjectMapper.class, JsonMapper.builder()::build)
            .withBean(NamedParameterJdbcTemplate.class,
                    () -> mock(NamedParameterJdbcTemplate.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(KnowledgeEmbeddingVectorStore.class,
                    () -> mock(KnowledgeEmbeddingVectorStore.class))
            // The chunk store moved to KnowledgeVectorConfiguration (M10-A01); the
            // worker still consumes it, so assembly tests supply it as a collaborator.
            .withBean(RepositoryChunkVectorStore.class,
                    () -> mock(RepositoryChunkVectorStore.class))
            // The generation store is NOT mocked: the configuration assembles the real
            // JDBC adapter behind the vector switch (same matrix as the worker), against
            // the mocked jdbc/transaction collaborators below.
            .withBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class))
            .withBean(TransactionExecutor.class, () -> mock(TransactionExecutor.class))
            .withBean(OperationalTelemetry.class, OperationalTelemetry::noop)
            .withBean(ManagedRepositoryResolver.class, () -> mock(ManagedRepositoryResolver.class))
            .withBean(GitCommandExecutor.class, () -> mock(GitCommandExecutor.class))
            // Control-plane collaborators (M10-I01c): the bean assembles behind no switch.
            .withBean(RepositoryBindingRepository.class,
                    () -> mock(RepositoryBindingRepository.class))
            .withBean(TeamRepository.class, () -> mock(TeamRepository.class))
            .withBean(TeamMembershipQuery.class, () -> mock(TeamMembershipQuery.class))
            .withBean(TeamRoleRepository.class, () -> mock(TeamRoleRepository.class))
            .withBean(MemberRoleRepository.class, () -> mock(MemberRoleRepository.class));

    @Test
    void indexWithoutVectorRejectsRefreshEnqueuesAndReportsDown() {
        runner.withPropertyValues("crewscope.knowledge.index.enabled=true").run(context -> {
            context.assertThat()
                    .hasNotFailed()
                    .hasSingleBean(KnowledgeIndexJobService.class)
                    .hasSingleBean(KnowledgeIndexControlService.class)
                    .hasSingleBean(KnowledgeIndexHealthIndicator.class)
                    .doesNotHaveBean(KnowledgeIndexStatusCatalog.class)
                    .doesNotHaveBean(KnowledgeIndexWorker.class)
                    .doesNotHaveBean(KnowledgeIndexWorkerScheduler.class);
            assertFalse(context.getBean(KnowledgeIndexJobService.class).refreshEnabled(),
                    "the illegal combination never accepts refresh enqueues");
            KnowledgeIndexHealthIndicator indicator =
                    context.getBean(KnowledgeIndexHealthIndicator.class);
            assertTrue(
                    Status.DOWN.equals(indicator.health().getStatus()),
                    "index without vector is DOWN, not silently healthy");
            assertTrue(indicator.health().getDetails().toString().contains("vector"));
        });
    }

    @Test
    void indexAndVectorOpenTheGatesAndAssembleTheProjectionBeans() {
        // The default execution profile is the all-in-one deployment: both switches on
        // assemble the worker too, while the poll loop still waits for its own switch.
        runner.withPropertyValues(
                        "crewscope.knowledge.vector.enabled=true",
                        "crewscope.knowledge.index.enabled=true")
                .run(context -> {
                    context.assertThat()
                            .hasNotFailed()
                            .hasSingleBean(KnowledgeIndexStatusCatalog.class)
                            .hasSingleBean(KnowledgeIndexControlService.class)
                            .hasSingleBean(KnowledgeIndexHealthIndicator.class)
                            .hasSingleBean(KnowledgeIndexWorker.class)
                            .doesNotHaveBean(KnowledgeIndexWorkerScheduler.class);
                    assertTrue(
                            context.getBean(KnowledgeIndexJobService.class).refreshEnabled());
                    assertTrue(Status.UP.equals(
                            context.getBean(KnowledgeIndexHealthIndicator.class)
                                    .health().getStatus()));
                });
    }

    @Test
    void theWorkerAndSchedulerNeedTheWorkerProfileAndEverySwitch() {
        String[] openSwitches = {
            "crewscope.knowledge.vector.enabled=true",
            "crewscope.knowledge.index.enabled=true"
        };
        // An api-only deployment never assembles leased execution even fully switched on.
        runner.withPropertyValues(
                        concat(openSwitches, "crewscope.runtime.execution-profile=api"))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .doesNotHaveBean(KnowledgeIndexWorker.class)
                        .doesNotHaveBean(KnowledgeIndexWorkerScheduler.class));
        // A worker profile with both switches assembles the worker; the poll loop still
        // waits for its own switch.
        runner.withPropertyValues(concat(openSwitches, "crewscope.runtime.execution-profile=worker"))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(KnowledgeIndexWorker.class)
                        .doesNotHaveBean(KnowledgeIndexWorkerScheduler.class));
        runner.withPropertyValues(concat(
                        openSwitches,
                        "crewscope.runtime.execution-profile=worker",
                        "crewscope.knowledge.index.worker.enabled=true"))
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .hasSingleBean(KnowledgeIndexWorker.class)
                        .hasSingleBean(KnowledgeIndexWorkerScheduler.class));
        // Vector off disables the worker even with the other switches on.
        runner.withPropertyValues(
                        "crewscope.knowledge.index.enabled=true",
                        "crewscope.knowledge.index.worker.enabled=true",
                        "crewscope.runtime.execution-profile=worker")
                .run(context -> context.assertThat()
                        .hasNotFailed()
                        .doesNotHaveBean(KnowledgeIndexWorker.class));
    }

    @Test
    void thePollingLoopNeverOverlapsItself() throws Exception {
        KnowledgeIndexWorker worker = mock(KnowledgeIndexWorker.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(worker.runOnce())
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return new KnowledgeIndexWorkerRunResult(1, 0, 0);
                });
        KnowledgeIndexWorkerScheduler scheduler =
                new KnowledgeIndexWorkerScheduler(worker, OperationalTelemetry.noop());

        CompletableFuture<Void> first = CompletableFuture.runAsync(scheduler::poll);
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first poll must start");
        // While the first pass is still inside runOnce, a second poll returns instantly
        // instead of queueing another claim behind it.
        CompletableFuture<Void> second = CompletableFuture.runAsync(scheduler::poll);
        second.get(1, TimeUnit.SECONDS);
        assertTrue(second.isDone() && !second.isCompletedExceptionally());

        release.countDown();
        first.get(5, TimeUnit.SECONDS);
        verify(worker, timeout(1000)).runOnce();
    }

    private static String[] concat(String[] values, String... more) {
        String[] all = new String[values.length + more.length];
        System.arraycopy(values, 0, all, 0, values.length);
        System.arraycopy(more, 0, all, values.length, more.length);
        return all;
    }
}
