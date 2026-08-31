package io.crewscope.infrastructure.persistence.modelagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.crewscope.application.agent.AgentConfigurationRepository;
import io.crewscope.application.agent.AgentInstance;
import io.crewscope.application.agent.AgentInstanceRepository;
import io.crewscope.application.agent.AgentModelDefaultRepository;
import io.crewscope.application.agent.AgentTemplateRepository;
import io.crewscope.application.model.ModelCatalogEntryRepository;
import io.crewscope.application.model.ModelConnectionRepository;
import io.crewscope.application.model.DefaultPlatformModelCatalogInitializer;
import io.crewscope.application.model.ModelPriceScheduleRepository;
import io.crewscope.application.model.ModelProviderDefinitionRepository;
import io.crewscope.application.team.AgentProfileRepository;
import io.crewscope.domain.agent.AgentConfigurableSlot;
import io.crewscope.domain.agent.AgentConfigurationVersion;
import io.crewscope.domain.agent.AgentDirectModelBinding;
import io.crewscope.domain.agent.AgentExecutionModelBinding;
import io.crewscope.domain.agent.AgentExecutionScope;
import io.crewscope.domain.agent.AgentModelDefault;
import io.crewscope.domain.agent.AgentModelDefaultScope;
import io.crewscope.domain.agent.AgentModelSelection;
import io.crewscope.domain.agent.AgentOwnership;
import io.crewscope.domain.agent.AgentOwnershipType;
import io.crewscope.domain.agent.AgentRuntimeRole;
import io.crewscope.domain.agent.AgentTemplateCapabilities;
import io.crewscope.domain.agent.AgentTemplateCapability;
import io.crewscope.domain.agent.AgentTemplateDefinition;
import io.crewscope.domain.agent.AgentTemplateKey;
import io.crewscope.domain.agent.AgentTemplatePolicy;
import io.crewscope.domain.agent.AgentTemplatePublisherScope;
import io.crewscope.domain.agent.AgentToolKey;
import io.crewscope.domain.agent.SafeModelGenerateOptions;
import io.crewscope.domain.identity.Principal;
import io.crewscope.domain.identity.PrincipalScope;
import io.crewscope.domain.identity.PrincipalType;
import io.crewscope.domain.identity.PrincipalVisibility;
import io.crewscope.domain.model.ModelAdapterKey;
import io.crewscope.domain.model.ModelBillingSubject;
import io.crewscope.domain.model.ModelCapability;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelCatalogEntryId;
import io.crewscope.domain.model.ModelConnection;
import io.crewscope.domain.model.ModelConnectionId;
import io.crewscope.domain.model.ModelConnectionOwner;
import io.crewscope.domain.model.ModelCredentialBinding;
import io.crewscope.domain.model.ModelCredentialSubject;
import io.crewscope.domain.model.ModelCredentialVersion;
import io.crewscope.domain.model.ModelDataPolicy;
import io.crewscope.domain.model.ModelEndpoint;
import io.crewscope.domain.model.ModelId;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelPriceSchedule;
import io.crewscope.domain.model.ModelPriceSource;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.model.ModelRegion;
import io.crewscope.domain.model.ModelRevision;
import io.crewscope.domain.model.ModelTokenPrice;
import io.crewscope.domain.policy.PolicyPackId;
import io.crewscope.domain.policy.PolicyPackReference;
import io.crewscope.domain.shared.audit.AuditMetadata;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.error.OptimisticLockConflictException;
import io.crewscope.domain.shared.id.CredentialId;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.id.PrincipalId;
import io.crewscope.domain.shared.id.TeamId;
import io.crewscope.domain.shared.id.WorkspaceId;
import io.crewscope.domain.shared.time.UtcTimestamp;
import io.crewscope.domain.workspace.AgentProfile;
import io.crewscope.domain.workspace.AgentProfileId;
import io.crewscope.domain.workspace.AgentProfileStatus;
import io.crewscope.domain.workspace.AgentProfileType;
import io.crewscope.domain.workspace.WorkspaceScope;
import io.crewscope.infrastructure.persistence.team.JpaAgentProfileRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.JpaAgentInstanceRepositoryAdapter;
import io.crewscope.infrastructure.persistence.team.TeamPersistenceMapper;
import io.crewscope.infrastructure.testcontainers.AbstractPostgresRedisContainerIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL contract for the complete M5 model, template and Agent configuration graph. */
@SpringBootTest(
        classes = M5I01ModelAgentPersistenceIntegrationTest.TestApplication.class,
        properties = {
            "spring.flyway.schemas=crewscope",
            "spring.flyway.default-schema=crewscope",
            "spring.flyway.create-schemas=true",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.properties.hibernate.default_schema=crewscope",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "spring.jpa.open-in-view=false"
        })
class M5I01ModelAgentPersistenceIntegrationTest
        extends AbstractPostgresRedisContainerIntegrationTest {

    private static final UtcTimestamp CREATED = UtcTimestamp.parse("2026-08-23T08:00:00Z");
    private static final UtcTimestamp VERIFIED = UtcTimestamp.parse("2026-08-23T08:01:00Z");
    private static final UtcTimestamp LATER = UtcTimestamp.parse("2026-08-23T08:02:00Z");
    private static final ModelRegion GLOBAL = new ModelRegion("global");

    @Autowired private ModelProviderDefinitionRepository providers;
    @Autowired private ModelCatalogEntryRepository catalogs;
    @Autowired private ModelPriceScheduleRepository prices;
    @Autowired private ModelConnectionRepository connections;
    @Autowired private AgentTemplateRepository templates;
    @Autowired private AgentProfileRepository profiles;
    @Autowired private AgentConfigurationRepository configurations;
    @Autowired private AgentModelDefaultRepository defaults;
    @Autowired private AgentInstanceRepository agentInstances;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TransactionTemplate transactions;

    @BeforeEach
    void resetBusinessData() {
        jdbc.execute("TRUNCATE TABLE crewscope.organization CASCADE");
    }

    @Test
    void idempotentlyPersistsTheCanonicalPlatformModelCatalog() {
        Fixture fixture = seedFixture("platform-catalog");
        DefaultPlatformModelCatalogInitializer initializer =
                new DefaultPlatformModelCatalogInitializer(providers, catalogs, prices);

        initializer.initialize(fixture.actorId(), CREATED);
        initializer.initialize(fixture.actorId(), LATER);

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.model_provider_definition",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.model_catalog_entry",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.model_price_revision",
                Integer.class));
        ModelCatalogEntry catalog = catalogs.findLatest(
                        new ModelProviderKey("deepseek"), new ModelId("deepseek-v4-flash"))
                .orElseThrow();
        ModelPriceRevision price = prices.findEffectivePrice(catalog.coordinate(), LATER)
                .orElseThrow();
        assertEquals("DeepSeek-V4-Flash-0731", catalog.modelRevision().toString());
        assertEquals("0.44", price.tokenPrice().inputPerMillionTokens().toPlainString());
        assertEquals("1.32", price.tokenPrice().outputPerMillionTokens().toPlainString());
        assertEquals("0.014", price.tokenPrice().cachedInputPerMillionTokens()
                .orElseThrow().toPlainString());
    }

    @Test
    void roundTripsTheCompleteRegistryConnectionTemplateProfileConfigurationAndDefaultGraph() {
        Fixture fixture = seedFixture("graph");
        PersistedGraph graph = persistGraph(fixture);

        assertEquals(graph.provider().contentHash(), providers
                .findByKey(graph.provider().providerKey()).orElseThrow().contentHash());
        assertEquals(graph.catalog().contentHash(), catalogs
                .findByCoordinate(graph.catalog().coordinate()).orElseThrow().contentHash());
        assertEquals(1, prices.findSchedule(graph.catalog().coordinate()).orElseThrow()
                .revisions().size());
        assertEquals(graph.price().contentHash(), prices
                .findEffectivePrice(graph.catalog().coordinate(), LATER)
                .orElseThrow().contentHash());
        ModelConnection loadedConnection = connections
                .findById(fixture.organizationId(), graph.connection().id()).orElseThrow();
        assertEquals(graph.connection().version(), loadedConnection.version());
        assertEquals(graph.connection().health(), loadedConnection.health());
        assertEquals(graph.connection().owner(), loadedConnection.owner());
        assertEquals(graph.template().contentHash(), templates
                .findByVersion(graph.template().publisherScope(), graph.template().templateVersion())
                .orElseThrow().contentHash());
        assertEquals(graph.profile().ownership(), profiles
                .findById(fixture.organizationId(), graph.profile().id()).orElseThrow().ownership());

        AgentConfigurationVersion loadedConfiguration = configurations
                .findCurrent(fixture.organizationId(), graph.profile().id()).orElseThrow();
        assertEquals(graph.configuration().configurationHash(), loadedConfiguration.configurationHash());
        assertEquals(graph.configuration().personalModelBinding(), loadedConfiguration.personalModelBinding());
        assertEquals(graph.configuration().teamModelBinding(), loadedConfiguration.teamModelBinding());
        assertEquals(graph.modelDefault().contentHash(), defaults
                .findCurrent(graph.modelDefault().scope(), graph.template().templateVersion(),
                        AgentExecutionScope.TEAM)
                .orElseThrow().contentHash());

        assertFalse(connections.findById(OrganizationId.generate(), graph.connection().id()).isPresent());
        assertFalse(configurations.findCurrent(OrganizationId.generate(), graph.profile().id()).isPresent());
        assertThrows(DomainValidationException.class, () -> defaults.findCurrent(
                AgentModelDefaultScope.organization(OrganizationId.generate()),
                graph.template().templateVersion(), AgentExecutionScope.TEAM));
    }

    @Test
    void atomicallyCreatesAndTransitionsAnAgentPrincipalProfilePair() {
        Fixture fixture = seedFixture("a02-instance");
        AgentTemplateDefinition template = templates.append(template(fixture));
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(fixture.organizationId(), fixture.teamId()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(fixture.actorId()),
                "A02 Team Coding",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                CREATED);
        AgentProfile profile = AgentProfile.reconstituteTemplateInstance(
                AgentProfileId.generate(),
                WorkspaceScope.team(fixture.organizationId(), fixture.teamId()),
                fixture.workspaceId(),
                principal.id(),
                AgentOwnership.team(fixture.organizationId(), fixture.teamId()),
                AgentRuntimeRole.SPECIALIST,
                template.templateVersion(),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.actorId(), CREATED));

        transactions.executeWithoutResult(ignored ->
                agentInstances.create(new AgentInstance(principal, profile)));

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM crewscope.principal WHERE id = ?",
                Integer.class,
                principal.id().value()));
        AgentProfile loaded = profiles.findById(
                fixture.organizationId(), profile.id()).orElseThrow();
        assertEquals(profile.id(), loaded.id());
        assertEquals(profile.ownership(), loaded.ownership());
        assertEquals(profile.templateVersion(), loaded.templateVersion());

        Principal disabledPrincipal = principal.transitionTo(
                io.crewscope.domain.identity.PrincipalStatus.DISABLED, LATER);
        AgentProfile disabledProfile = profile.disable(fixture.actorId(), LATER);
        transactions.executeWithoutResult(ignored -> agentInstances.updateLifecycle(
                new AgentInstance(disabledPrincipal, disabledProfile)));

        assertEquals("DISABLED", jdbc.queryForObject(
                "SELECT status FROM crewscope.principal WHERE id = ?",
                String.class,
                principal.id().value()));
        assertEquals(AgentProfileStatus.DISABLED, profiles.findById(
                fixture.organizationId(), profile.id()).orElseThrow().status());
    }

    @Test
    void rollsBackPrincipalLifecycleWhenTheProfileVersionConflicts() {
        Fixture fixture = seedFixture("a02-instance-rollback");
        AgentTemplateDefinition template = templates.append(template(fixture));
        Principal principal = Principal.create(
                PrincipalId.generate(),
                PrincipalScope.team(fixture.organizationId(), fixture.teamId()),
                PrincipalType.SPECIALIST_AGENT,
                Optional.of(fixture.actorId()),
                "A02 Atomic Coding",
                Optional.empty(),
                PrincipalVisibility.TEAM,
                CREATED);
        AgentProfile profile = AgentProfile.reconstituteTemplateInstance(
                AgentProfileId.generate(),
                WorkspaceScope.team(fixture.organizationId(), fixture.teamId()),
                fixture.workspaceId(),
                principal.id(),
                AgentOwnership.team(fixture.organizationId(), fixture.teamId()),
                AgentRuntimeRole.SPECIALIST,
                template.templateVersion(),
                AgentProfileType.SPECIALIST,
                false,
                AgentProfileStatus.ACTIVE,
                0,
                AuditMetadata.createdBy(fixture.actorId(), CREATED));
        transactions.executeWithoutResult(ignored ->
                agentInstances.create(new AgentInstance(principal, profile)));
        jdbc.update(
                "UPDATE crewscope.agent_profile SET version = 1 WHERE organization_id = ? AND id = ?",
                fixture.organizationId().value(),
                profile.id().value());

        AgentInstance disabled = new AgentInstance(
                principal.transitionTo(
                        io.crewscope.domain.identity.PrincipalStatus.DISABLED, LATER),
                profile.disable(fixture.actorId(), LATER));
        assertThrows(
                OptimisticLockConflictException.class,
                () -> transactions.executeWithoutResult(
                        ignored -> agentInstances.updateLifecycle(disabled)));

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM crewscope.principal WHERE id = ?",
                String.class,
                principal.id().value()));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT version FROM crewscope.principal WHERE id = ?",
                Long.class,
                principal.id().value()));
    }

    @Test
    void latestActiveTemplatePageDoesNotFallBackToAnOlderVersion() {
        Fixture fixture = seedFixture("a02-catalog");
        AgentTemplateDefinition first = templates.append(template(fixture));
        AgentTemplateDefinition second = templates.append(first.publishNext(
                first.allowedOwnershipTypes(),
                first.allowedExecutionScopes(),
                first.capabilities(),
                first.policy(),
                fixture.actorId(),
                VERIFIED));

        List<AgentTemplateDefinition> latest = templates.findLatestActivePage(
                first.publisherScope(), 0, 20);
        assertEquals(1, latest.size());
        assertEquals(second.templateVersion(), latest.get(0).templateVersion());
        assertEquals(second.contentHash(), latest.get(0).contentHash());

        templates.updateLifecycle(second.disable(fixture.actorId(), LATER));
        assertTrue(templates.findLatestActivePage(first.publisherScope(), 0, 20).isEmpty());
    }

    @Test
    void resolvesModelDefaultAgainstTheExactTemplateContentAcrossPublishers() {
        Fixture fixture = seedFixture("default-template");
        PersistedGraph graph = persistGraph(fixture);
        AgentTemplateDefinition teamTemplate = templates.append(AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.team(fixture.organizationId(), fixture.teamId()),
                graph.template().templateVersion().key(), AgentRuntimeRole.SPECIALIST,
                graph.template().allowedOwnershipTypes(), graph.template().allowedExecutionScopes(),
                graph.template().capabilities(),
                AgentTemplatePolicy.define(
                        "Perform the approved team coding task.",
                        graph.template().policy().allowedTools(),
                        graph.template().policy().approvedSkillKeys(), Optional.empty(),
                        graph.template().policy().memberConfigurableSlots(),
                        graph.template().policy().administratorConfigurableSlots()),
                fixture.actorId(), LATER));
        AgentModelDefault teamDefault = defaults.append(AgentModelDefault.publishInitial(
                teamTemplate,
                AgentModelDefaultScope.team(fixture.organizationId(), fixture.teamId()),
                AgentExecutionScope.PERSONAL, graph.modelDefault().modelBinding(),
                policyPack(2), fixture.actorId(), LATER));

        AgentModelDefault loaded = defaults.findCurrent(
                teamDefault.scope(), teamDefault.templateVersion(),
                AgentExecutionScope.PERSONAL).orElseThrow();

        assertEquals(teamTemplate.contentHash(), loaded.templateContentHash());
        assertEquals(teamDefault.contentHash(), loaded.contentHash());
    }

    @Test
    void providesStableBoundedPagesWithoutPerConfigurationProfileQueries() {
        Fixture fixture = seedFixture("page");
        PersistedGraph graph = persistGraph(fixture);
        AgentConfigurationVersion second = appendConfiguration(graph, "second", LATER);
        AgentConfigurationVersion third = appendConfiguration(
                graph.withConfiguration(second), "third",
                UtcTimestamp.parse("2026-08-23T08:03:00Z"));

        assertEquals(List.of(third.revision(), second.revision()), configurations
                .findPage(fixture.organizationId(), graph.profile().id(), 0, 2)
                .stream().map(AgentConfigurationVersion::revision).toList());
        assertEquals(List.of(graph.configuration().revision()), configurations
                .findPage(fixture.organizationId(), graph.profile().id(), 2, 2)
                .stream().map(AgentConfigurationVersion::revision).toList());
        assertEquals(List.of(graph.provider().providerKey()), providers.findPage(0, 20)
                .stream().map(ModelProviderDefinition::providerKey).toList());
        assertEquals(List.of(graph.catalog().coordinate()), catalogs
                .findPage(graph.provider().providerKey(), 0, 20)
                .stream().map(ModelCatalogEntry::coordinate).toList());
        assertEquals(List.of(graph.template().templateVersion()), templates
                .findPage(graph.template().publisherScope(), 0, 20)
                .stream().map(AgentTemplateDefinition::templateVersion).toList());
        assertEquals(List.of(graph.profile().id()), profiles.findPage(fixture.organizationId(), 0, 20)
                .stream().map(AgentProfile::id).toList());
        assertThrows(DomainValidationException.class,
                () -> configurations.findPage(fixture.organizationId(), graph.profile().id(), 0, 201));

        // The JDBC graph query loads every Binding at once; JPA is used once for the stable Profile.
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getStatistics().clear();
        configurations.findPage(fixture.organizationId(), graph.profile().id(), 0, 1);
        long oneRowProfileQueries = sessionFactory.getStatistics().getPrepareStatementCount();
        sessionFactory.getStatistics().clear();
        configurations.findPage(fixture.organizationId(), graph.profile().id(), 0, 3);
        long threeRowProfileQueries = sessionFactory.getStatistics().getPrepareStatementCount();
        assertEquals(1, oneRowProfileQueries);
        assertEquals(oneRowProfileQueries, threeRowProfileQueries);
    }

    @Test
    void serializesConcurrentConfigurationAndDefaultRevisionAppends() throws Exception {
        Fixture fixture = seedFixture("concurrency");
        PersistedGraph graph = persistGraph(fixture);
        ModelCatalogEntry nextCatalog = graph.catalog().publishNext(
                graph.provider(), new ModelRevision("2026-08-23.2"), "Model concurrency v2",
                160_000, 12_000, graph.catalog().capabilities(), graph.catalog().availableRegions(),
                fixture.actorId(), LATER);
        AgentTemplateDefinition nextTemplate = graph.template().publishNext(
                graph.template().allowedOwnershipTypes(), graph.template().allowedExecutionScopes(),
                graph.template().capabilities(), graph.template().policy(), fixture.actorId(), LATER);
        AgentConfigurationVersion nextConfiguration = graph.configuration().appendNext(
                graph.profile(), graph.template(),
                graph.configuration().personalModelBinding(), graph.configuration().teamModelBinding(),
                Optional.of("Concurrent revision"), Set.of("secure-coding"),
                Optional.empty(), Optional.empty(), policyPack(2),
                SafeModelGenerateOptions.defaults(), fixture.actorId(), LATER);
        AgentModelDefault nextDefault = graph.modelDefault().publishNext(
                graph.template(), graph.modelDefault().modelBinding(), policyPack(2),
                fixture.actorId(), LATER);

        assertSingleWinner(() -> catalogs.append(nextCatalog));
        assertSingleWinner(() -> templates.append(nextTemplate));
        assertSingleWinner(() -> configurations.append(nextConfiguration));
        assertSingleWinner(() -> defaults.append(nextDefault));
        assertEquals(nextCatalog.catalogRevision(), catalogs.findLatest(
                nextCatalog.providerKey(), nextCatalog.modelId()).orElseThrow().catalogRevision());
        assertEquals(nextTemplate.templateVersion(), templates.findLatest(
                nextTemplate.publisherScope(), nextTemplate.templateVersion().key())
                .orElseThrow().templateVersion());
        assertEquals(nextConfiguration.configurationHash(), configurations
                .findCurrent(fixture.organizationId(), graph.profile().id())
                .orElseThrow().configurationHash());
        assertEquals(nextDefault.contentHash(), defaults.findCurrent(
                nextDefault.scope(), nextDefault.templateVersion(), nextDefault.executionScope())
                .orElseThrow().contentHash());
        assertEquals(1, defaults.findCurrentCandidates(
                nextDefault.scope(), nextDefault.templateVersion(), nextDefault.executionScope()).size());
    }

    @Test
    void rejectsCrossScopeWritesAtTheDatabaseVerdict() {
        Fixture first = seedFixture("scope-a");
        PersistedGraph graph = persistGraph(first);
        Fixture second = seedFixture("scope-b");

        ModelConnection forged = ModelConnection.open(
                graph.provider(), ModelConnectionId.generate(),
                teamOwner(second),
                new ModelEndpoint("https://gateway.example.com/forged"), GLOBAL,
                new ModelCredentialBinding(
                        graph.connection().credentialBinding().credentialId(),
                        ModelCredentialSubject.team(second.organizationId(), second.teamId()),
                        new ModelCredentialVersion(0)),
                ModelBillingSubject.team(second.organizationId(), second.teamId()),
                second.actorId(), CREATED);

        // V23 defers the credential FK so atomic secret rotation can update both sides in one
        // transaction. A forged final graph is therefore rejected by PostgreSQL at commit time.
        assertThrows(DataIntegrityViolationException.class, () -> connections.register(forged));
        assertFalse(connections.findById(second.organizationId(), graph.connection().id()).isPresent());
    }

    @Test
    void appliesOptimisticLifecycleUpdatesWithoutChangingImmutableContentHashes() {
        Fixture fixture = seedFixture("lifecycle");
        PersistedGraph graph = persistGraph(fixture);

        ModelProviderDefinition disabledProvider = graph.provider().disable(fixture.actorId(), LATER);
        ModelCatalogEntry disabledCatalog = graph.catalog().disable(fixture.actorId(), LATER);
        AgentTemplateDefinition disabledTemplate = graph.template().disable(fixture.actorId(), LATER);

        assertEquals(disabledProvider.lifecycleVersion(),
                providers.updateLifecycle(disabledProvider).lifecycleVersion());
        assertEquals(disabledCatalog.lifecycleVersion(),
                catalogs.updateLifecycle(disabledCatalog).lifecycleVersion());
        assertEquals(disabledTemplate.lifecycleVersion(),
                templates.updateLifecycle(disabledTemplate).lifecycleVersion());
        assertEquals(graph.provider().contentHash(), disabledProvider.contentHash());
        assertEquals(graph.catalog().contentHash(), disabledCatalog.contentHash());
        assertEquals(graph.template().contentHash(), disabledTemplate.contentHash());

        assertThrows(DomainValidationException.class,
                () -> providers.updateLifecycle(disabledProvider));
        assertThrows(DomainValidationException.class,
                () -> catalogs.updateLifecycle(disabledCatalog));
        assertThrows(DomainValidationException.class,
                () -> templates.updateLifecycle(disabledTemplate));
    }

    private PersistedGraph persistGraph(Fixture fixture) {
        ModelProviderDefinition provider = providers.register(provider("provider-" + fixture.key(), fixture));
        ModelCatalogEntry catalog = catalogs.append(ModelCatalogEntry.publishInitial(
                provider, ModelCatalogEntryId.generate(), new ModelId("model-" + fixture.key()),
                new ModelRevision("2026-08-23"), "Model " + fixture.key(), 128_000, 8_192,
                Set.of(new ModelCapability("tool-calling")), Set.of(GLOBAL),
                fixture.actorId(), CREATED));
        ModelPriceSchedule schedule = ModelPriceSchedule.start(
                provider, catalog, CREATED,
                new ModelTokenPrice(new BigDecimal("0.10"), new BigDecimal("0.30"),
                        Optional.of(new BigDecimal("0.05")), "USD"),
                new ModelPriceSource("integration-fixture"), fixture.actorId(), CREATED);
        ModelPriceRevision price = prices.append(schedule.revisions().get(0));

        ModelConnection opened = ModelConnection.open(
                provider, ModelConnectionId.generate(),
                teamOwner(fixture),
                new ModelEndpoint("https://gateway.example.com/" + fixture.key()), GLOBAL,
                new ModelCredentialBinding(
                        fixture.credentialId(),
                        ModelCredentialSubject.team(fixture.organizationId(), fixture.teamId()),
                        new ModelCredentialVersion(0)),
                ModelBillingSubject.team(fixture.organizationId(), fixture.teamId()),
                fixture.actorId(), CREATED);
        ModelConnection registered = connections.register(opened);
        ModelConnection connection = connections.update(registered.recordVerificationSuccess(
                provider, registered.version(), new ModelCredentialVersion(0),
                fixture.actorId(), VERIFIED));

        AgentTemplateDefinition template = templates.append(template(fixture));
        AgentProfile profile = profiles.create(AgentProfile.reconstituteTemplateInstance(
                AgentProfileId.generate(),
                WorkspaceScope.team(fixture.organizationId(), fixture.teamId()),
                fixture.workspaceId(), fixture.agentPrincipalId(),
                AgentOwnership.team(fixture.organizationId(), fixture.teamId()),
                AgentRuntimeRole.SPECIALIST, template.templateVersion(),
                AgentProfileType.SPECIALIST, false, AgentProfileStatus.ACTIVE, 0,
                AuditMetadata.createdBy(fixture.actorId(), CREATED)));
        AgentModelSelection selection = AgentModelSelection.capture(connection, catalog);
        AgentDirectModelBinding direct = new AgentDirectModelBinding(selection, Optional.empty());
        AgentConfigurationVersion configuration = configurations.append(
                AgentConfigurationVersion.createInitial(
                        profile, template, Optional.empty(),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.PERSONAL, direct)),
                        Optional.of(AgentExecutionModelBinding.direct(
                                AgentExecutionScope.TEAM, direct)),
                        Optional.of("Keep changes inside the approved task."),
                        Set.of("secure-coding"), Optional.empty(), Optional.empty(),
                        policyPack(1), SafeModelGenerateOptions.defaults(),
                        fixture.actorId(), CREATED));
        AgentModelDefault modelDefault = defaults.append(AgentModelDefault.publishInitial(
                template, AgentModelDefaultScope.team(fixture.organizationId(), fixture.teamId()),
                AgentExecutionScope.TEAM, direct, policyPack(1), fixture.actorId(), CREATED));
        return new PersistedGraph(
                fixture, provider, catalog, price, connection, template, profile,
                configuration, modelDefault);
    }

    private AgentConfigurationVersion appendConfiguration(
            PersistedGraph graph, String instructions, UtcTimestamp occurredAt) {
        AgentConfigurationVersion next = graph.configuration().appendNext(
                graph.profile(), graph.template(),
                graph.configuration().personalModelBinding(), graph.configuration().teamModelBinding(),
                Optional.of(instructions), Set.of("secure-coding"), Optional.empty(), Optional.empty(),
                policyPack(graph.configuration().revision().value() + 1),
                SafeModelGenerateOptions.defaults(), graph.fixture().actorId(), occurredAt);
        return configurations.append(next);
    }

    private static ModelProviderDefinition provider(String key, Fixture fixture) {
        return ModelProviderDefinition.publish(
                new ModelProviderKey(key), "Provider " + fixture.key(),
                new ModelAdapterKey("openai-compatible"),
                new ModelEndpoint("https://api.example.com/" + fixture.key()), Set.of(GLOBAL),
                ModelDataPolicy.noRetention(), fixture.actorId(), CREATED);
    }

    private static AgentTemplateDefinition template(Fixture fixture) {
        return AgentTemplateDefinition.publishInitial(
                AgentTemplatePublisherScope.organization(fixture.organizationId()),
                new AgentTemplateKey("coding-" + fixture.key()), AgentRuntimeRole.SPECIALIST,
                Set.of(AgentOwnershipType.TEAM),
                Set.of(AgentExecutionScope.PERSONAL, AgentExecutionScope.TEAM),
                AgentTemplateCapabilities.define(
                        Set.of(new AgentTemplateCapability("source-code.change")),
                        Set.of(new AgentTemplateCapability("model.tool-calling"))),
                AgentTemplatePolicy.define(
                        "Perform the approved coding task.",
                        Set.of(new AgentToolKey("repository.read")), Set.of("secure-coding"),
                        Optional.empty(),
                        Set.of(AgentConfigurableSlot.SUPPLEMENTAL_INSTRUCTIONS,
                                AgentConfigurableSlot.APPROVED_SKILLS),
                        Set.of(AgentConfigurableSlot.MODEL_BINDING)),
                fixture.actorId(), CREATED);
    }

    private static PolicyPackReference policyPack(long version) {
        return new PolicyPackReference(
                new PolicyPackId(UUID.fromString("00000000-0000-0000-0000-000000000501")), version);
    }

    private static ModelConnectionOwner teamOwner(Fixture fixture) {
        return new ModelConnectionOwner(
                fixture.organizationId(),
                io.crewscope.domain.model.ModelConnectionOwnerType.TEAM,
                fixture.teamId().value(),
                Optional.of(fixture.teamId()),
                Optional.empty());
    }

    private static void assertSingleWinner(ThrowingWrite write) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> runWrite(write, ready, start, successes, conflicts));
            Future<?> second = executor.submit(() -> runWrite(write, ready, start, successes, conflicts));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
    }

    private static void runWrite(
            ThrowingWrite write,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicInteger successes,
            AtomicInteger conflicts) {
        ready.countDown();
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            Object result = write.execute();
            assertNotNull(result);
            successes.incrementAndGet();
        } catch (DomainValidationException expected) {
            conflicts.incrementAndGet();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private Fixture seedFixture(String key) {
        Fixture fixture = new Fixture(
                key, OrganizationId.generate(), TeamId.generate(), WorkspaceId.generate(),
                PrincipalId.generate(), PrincipalId.generate(), CredentialId.generate());
        jdbc.update("INSERT INTO crewscope.organization (id, name, status) VALUES (?, ?, 'ACTIVE')",
                fixture.organizationId().value(), "Organization " + key);
        jdbc.update("INSERT INTO crewscope.team (id, organization_id, name, status) VALUES (?, ?, ?, 'ACTIVE')",
                fixture.teamId().value(), fixture.organizationId().value(), "Team " + key);
        jdbc.update("""
                INSERT INTO crewscope.workspace (
                    id, organization_id, team_id, workspace_type, name, status
                ) VALUES (?, ?, ?, 'TEAM', ?, 'ACTIVE')
                """, fixture.workspaceId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), "Workspace " + key);
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES (?, ?, ?, 'USER', ?, 'TEAM', 'ACTIVE')
                """, fixture.actorId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), "Actor " + key);
        jdbc.update("""
                INSERT INTO crewscope.principal (
                    id, organization_id, team_id, principal_type,
                    display_name, visibility, status
                ) VALUES (?, ?, ?, 'SPECIALIST_AGENT', ?, 'TEAM', 'ACTIVE')
                """, fixture.agentPrincipalId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), "Coding Agent " + key);
        jdbc.update("""
                INSERT INTO crewscope.credential_secret (
                    id, organization_id, team_id, subject_type, subject_id,
                    credential_key, provider_key, credential_type,
                    ciphertext, nonce, authentication_tag, key_id, algorithm,
                    aad_version, metadata, status, version
                ) VALUES (?, ?, ?, 'TEAM', ?, ?, ?, 'API_KEY',
                    ?, ?, ?, 'test-key', 'AES-256-GCM', 'v1', '{}'::jsonb, 'ACTIVE', 0)
                """, fixture.credentialId().value(), fixture.organizationId().value(),
                fixture.teamId().value(), fixture.teamId().value(), "credential-" + key,
                "provider-" + key, new byte[] {1}, new byte[12], new byte[16]);
        return fixture;
    }

    @FunctionalInterface
    private interface ThrowingWrite {
        Object execute();
    }

    private record Fixture(
            String key,
            OrganizationId organizationId,
            TeamId teamId,
            WorkspaceId workspaceId,
            PrincipalId actorId,
            PrincipalId agentPrincipalId,
            CredentialId credentialId) {}

    private record PersistedGraph(
            Fixture fixture,
            ModelProviderDefinition provider,
            ModelCatalogEntry catalog,
            ModelPriceRevision price,
            ModelConnection connection,
            AgentTemplateDefinition template,
            AgentProfile profile,
            AgentConfigurationVersion configuration,
            AgentModelDefault modelDefault) {

        PersistedGraph withConfiguration(AgentConfigurationVersion next) {
            return new PersistedGraph(
                    fixture, provider, catalog, price, connection, template, profile,
                    next, modelDefault);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "io.crewscope.infrastructure.persistence.team")
    @Import({
        ModelAgentPersistenceMapper.class,
        JdbcModelRegistryRepositoryAdapter.class,
        JdbcModelConnectionRepositoryAdapter.class,
        JdbcAgentTemplateRepositoryAdapter.class,
        JdbcAgentConfigurationRepositoryAdapter.class,
        JdbcAgentModelDefaultRepositoryAdapter.class,
        TeamPersistenceMapper.class,
        JpaAgentProfileRepositoryAdapter.class,
        JpaAgentInstanceRepositoryAdapter.class
    })
    static class TestApplication {

        /** Mirrors the server's explicit Jackson 2 compatibility boundary. */
        @Bean
        ObjectMapper legacyObjectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
