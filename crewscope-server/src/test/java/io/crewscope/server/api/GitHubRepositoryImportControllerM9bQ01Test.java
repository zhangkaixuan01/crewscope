package io.crewscope.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.crewscope.application.github.GitHubRepositoryImportApplicationService;
import io.crewscope.application.github.GitHubRepositoryImportJobNotFoundException;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * M9b-Q01 R01 regression: a missing import job used to surface as a bare
 * {@code IllegalArgumentException} and therefore a generic 500. All three job endpoints must now
 * answer with the stable {@code 404 github_import_job_not_found} contract the API documentation
 * promises (same shape as {@code 409 github_conflict}).
 */
class GitHubRepositoryImportControllerM9bQ01Test {

    private static final UtcTimestamp NOW = UtcTimestamp.parse("2026-09-27T02:00:00Z");
    private static final UUID JOB_ID = UUID.randomUUID();

    private final OrganizationId organizationId = OrganizationId.generate();
    private GitHubRepositoryImportApplicationService service;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(GitHubRepositoryImportApplicationService.class);
        Principal actor = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.organization(organizationId),
                PrincipalType.USER,
                Optional.empty(),
                "Repository administrator",
                Optional.empty(),
                PrincipalVisibility.ORGANIZATION,
                NOW);
        TeamRequestIdentityResolver resolver = (authentication, organization, correlationId) ->
                Mono.just(new TeamAccessContext(actor, true));
        client = WebTestClient.bindToController(new GitHubRepositoryImportController(service, resolver))
                .controllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void missingJobAnswersStableNotFoundOnGetCancelAndRetry() {
        when(service.get(any(), any(), any(), any(), any()))
                .thenThrow(new GitHubRepositoryImportJobNotFoundException(JOB_ID));
        when(service.cancel(any(), any(), any(), any(), any()))
                .thenThrow(new GitHubRepositoryImportJobNotFoundException(JOB_ID));
        when(service.retry(any(), any(), any(), any(), any()))
                .thenThrow(new GitHubRepositoryImportJobNotFoundException(JOB_ID));

        String base = "/api/v1/organizations/" + organizationId
                + "/teams/00000000-0000-0000-0000-000000000201"
                + "/work-projects/00000000-0000-0000-0000-000000000401"
                + "/github-imports/" + JOB_ID;

        client.get().uri(base).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("github_import_job_not_found")
                .jsonPath("$.details.jobId").isEqualTo(JOB_ID.toString());

        client.post().uri(base + "/cancel")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m9b-q01-cancel")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("github_import_job_not_found");

        client.post().uri(base + "/retry")
                .header(ApiHeaders.IDEMPOTENCY_KEY, "m9b-q01-retry")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("github_import_job_not_found");
    }
}
