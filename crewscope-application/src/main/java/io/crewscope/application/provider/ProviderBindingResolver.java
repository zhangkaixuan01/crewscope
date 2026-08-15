package io.crewscope.application.provider;

import io.crewscope.domain.provider.Connection;
import io.crewscope.domain.provider.ConnectionGrant;
import io.crewscope.domain.provider.ProviderBinding;
import io.crewscope.domain.provider.ProviderBindingId;
import io.crewscope.domain.provider.ProviderBindingTargetType;
import io.crewscope.domain.provider.ProviderDefinition;
import io.crewscope.domain.provider.ProviderImplementation;
import io.crewscope.domain.provider.ProviderOwnerType;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.id.OrganizationId;
import io.crewscope.domain.shared.time.TimeProvider;
import io.crewscope.domain.shared.time.UtcTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one current Provider Binding without changing Provider facts.
 *
 * <p>Explicit and higher-scope bindings occupy their level even when current authorization facts
 * make them unusable. This prevents a revoked or stale narrow binding from silently falling back
 * to a broader lower-priority identity.
 */
public final class ProviderBindingResolver {

    private final ProviderBindingRepository bindingRepository;
    private final ProviderDefinitionRepository definitionRepository;
    private final ProviderImplementationRepository implementationRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionGrantRepository connectionGrantRepository;
    private final TimeProvider timeProvider;

    public ProviderBindingResolver(
            ProviderBindingRepository bindingRepository,
            ProviderDefinitionRepository definitionRepository,
            ProviderImplementationRepository implementationRepository,
            ConnectionRepository connectionRepository,
            ConnectionGrantRepository connectionGrantRepository,
            TimeProvider timeProvider) {
        this.bindingRepository = Objects.requireNonNull(bindingRepository, "bindingRepository");
        this.definitionRepository = Objects.requireNonNull(
                definitionRepository, "definitionRepository");
        this.implementationRepository = Objects.requireNonNull(
                implementationRepository, "implementationRepository");
        this.connectionRepository = Objects.requireNonNull(
                connectionRepository, "connectionRepository");
        this.connectionGrantRepository = Objects.requireNonNull(
                connectionGrantRepository, "connectionGrantRepository");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    /** Applies explicit precedence, then resolves the highest configured automatic level. */
    public ProviderBindingResolution resolve(ProviderBindingResolutionRequest request) {
        ProviderBindingResolutionRequest required = Objects.requireNonNull(request, "request");
        UtcTimestamp now = timeProvider.now();
        if (required.actionBindingId().isPresent()) {
            return resolveExplicit(
                    required,
                    required.actionBindingId().orElseThrow(),
                    ProviderBindingResolutionLevel.ACTION_EXPLICIT,
                    now);
        }
        if (required.taskBindingId().isPresent()) {
            return resolveExplicit(
                    required,
                    required.taskBindingId().orElseThrow(),
                    ProviderBindingResolutionLevel.TASK_EXPLICIT,
                    now);
        }

        List<ProviderBinding> rawCandidates = bindingRepository.findCandidates(
                new ProviderBindingQuery(
                        required.organizationId(),
                        required.teamId(),
                        required.workspaceId(),
                        required.workProjectId(),
                        required.bindingOwner(),
                        required.providerType(),
                        required.executionIdentity()));
        List<ProviderBinding> projectCandidates = rawCandidates.stream()
                .filter(binding -> binding.target().type()
                        == ProviderBindingTargetType.WORK_PROJECT)
                .filter(binding -> matchesTrustedRequest(binding, required))
                .toList();
        if (!projectCandidates.isEmpty()) {
            return resolveLevel(
                    projectCandidates,
                    required,
                    ProviderBindingResolutionLevel.WORK_PROJECT,
                    now);
        }

        List<ProviderBinding> workspaceCandidates = rawCandidates.stream()
                .filter(binding -> binding.target().type()
                        == ProviderBindingTargetType.WORKSPACE)
                .filter(binding -> matchesTrustedRequest(binding, required))
                .toList();
        if (!workspaceCandidates.isEmpty()) {
            ProviderBindingResolutionLevel level =
                    required.bindingOwner().type() == ProviderOwnerType.ORGANIZATION
                            ? ProviderBindingResolutionLevel.ORGANIZATION_DEFAULT
                            : ProviderBindingResolutionLevel.WORKSPACE;
            return resolveLevel(workspaceCandidates, required, level, now);
        }
        return ProviderBindingResolution.notFound(ProviderBindingResolutionLevel.NONE);
    }

    /**
     * Revalidates one explicit Binding against its currently pinned registry, Connection and Grant
     * facts without applying automatic fallback.
     */
    public Optional<ProviderBindingCandidate> resolveCurrent(
            OrganizationId organizationId, ProviderBindingId bindingId) {
        OrganizationId organization = Objects.requireNonNull(organizationId, "organizationId");
        ProviderBindingId id = Objects.requireNonNull(bindingId, "bindingId");
        return bindingRepository.findById(organization, id)
                .filter(binding -> binding.organizationId().equals(organization))
                .flatMap(binding -> currentCandidate(binding, organization, timeProvider.now()));
    }

    private ProviderBindingResolution resolveExplicit(
            ProviderBindingResolutionRequest request,
            ProviderBindingId bindingId,
            ProviderBindingResolutionLevel level,
            UtcTimestamp now) {
        Optional<ProviderBinding> binding = bindingRepository.findById(
                request.organizationId(), bindingId);
        if (binding.isEmpty() || !matchesTrustedRequest(binding.orElseThrow(), request)) {
            return ProviderBindingResolution.notFound(level);
        }
        return currentCandidate(binding.orElseThrow(), request, now)
                .map(candidate -> ProviderBindingResolution.resolved(level, candidate))
                .orElseGet(() -> ProviderBindingResolution.notFound(level));
    }

    private ProviderBindingResolution resolveLevel(
            List<ProviderBinding> rawCandidates,
            ProviderBindingResolutionRequest request,
            ProviderBindingResolutionLevel level,
            UtcTimestamp now) {
        List<ProviderBinding> configured = distinctBindings(rawCandidates);
        List<ProviderBinding> defaults = configured.stream()
                .filter(ProviderBinding::defaultUsage)
                .toList();
        if (defaults.size() > 1) {
            // The V7 unique index prevents this in PostgreSQL. Treat corrupted or non-database
            // adapters as ambiguous instead of choosing by incidental list order.
            return ProviderBindingResolution.ambiguous(level, ids(defaults));
        }
        if (defaults.size() == 1) {
            return currentCandidate(defaults.get(0), request, now)
                    .map(candidate -> ProviderBindingResolution.resolved(level, candidate))
                    .orElseGet(() -> ProviderBindingResolution.notFound(level));
        }

        List<ProviderBindingCandidate> current =
                distinctCurrentCandidates(configured, request, now);
        if (current.isEmpty()) {
            return ProviderBindingResolution.notFound(level);
        }
        if (current.size() > 1) {
            return ProviderBindingResolution.ambiguous(
                    level,
                    current.stream()
                            .map(candidate -> candidate.binding().id())
                            .toList());
        }
        return ProviderBindingResolution.resolved(level, current.get(0));
    }

    private static List<ProviderBinding> distinctBindings(List<ProviderBinding> bindings) {
        Map<ProviderBindingId, ProviderBinding> distinct = new LinkedHashMap<>();
        bindings.stream()
                .sorted(Comparator.comparing(binding -> binding.id().toString()))
                .forEach(binding -> distinct.putIfAbsent(binding.id(), binding));
        return List.copyOf(distinct.values());
    }

    private List<ProviderBindingCandidate> distinctCurrentCandidates(
            List<ProviderBinding> bindings,
            ProviderBindingResolutionRequest request,
            UtcTimestamp now) {
        Map<ProviderBindingId, ProviderBindingCandidate> distinct = new LinkedHashMap<>();
        bindings.stream()
                .sorted(Comparator.comparing(binding -> binding.id().toString()))
                .map(binding -> currentCandidate(binding, request, now))
                .flatMap(Optional::stream)
                .forEach(candidate -> distinct.putIfAbsent(
                        candidate.binding().id(), candidate));
        return List.copyOf(distinct.values());
    }

    private Optional<ProviderBindingCandidate> currentCandidate(
            ProviderBinding binding,
            ProviderBindingResolutionRequest request,
            UtcTimestamp now) {
        return currentCandidate(binding, request.organizationId(), now)
                .flatMap(candidate -> candidate.narrowTo(request.requestedAccess()));
    }

    private Optional<ProviderBindingCandidate> currentCandidate(
            ProviderBinding binding, OrganizationId organizationId, UtcTimestamp now) {
        Optional<ProviderDefinition> definition = definitionRepository.findById(
                organizationId, binding.definitionId());
        Optional<ProviderImplementation> implementation =
                implementationRepository.findById(
                        organizationId, binding.implementationId());
        if (definition.isEmpty() || implementation.isEmpty()) {
            return Optional.empty();
        }

        Optional<Connection> connection = binding.connectionId()
                .flatMap(id -> connectionRepository.findById(organizationId, id));
        Optional<ConnectionGrant> grant = binding.connectionGrantId()
                .flatMap(id -> connectionGrantRepository.findById(organizationId, id));
        if (binding.connectionId().isPresent() != connection.isPresent()
                || binding.connectionGrantId().isPresent() != grant.isPresent()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ProviderBindingCandidate.resolve(
                    binding,
                    definition.orElseThrow(),
                    implementation.orElseThrow(),
                    connection,
                    grant,
                    now));
        } catch (DomainValidationException exception) {
            return Optional.empty();
        }
    }

    private static boolean matchesTrustedRequest(
            ProviderBinding binding, ProviderBindingResolutionRequest request) {
        boolean targetMatches = binding.target().organizationId().equals(request.organizationId())
                && binding.target().teamId().equals(request.teamId())
                && binding.target().workspaceId().equals(request.workspaceId())
                && (binding.target().type() == ProviderBindingTargetType.WORKSPACE
                        || binding.target().workProjectId().equals(request.workProjectId()));
        return targetMatches
                && binding.owner().equals(request.bindingOwner())
                && binding.providerType() == request.providerType()
                && binding.executionIdentity().equals(request.executionIdentity());
    }

    private static List<ProviderBindingId> ids(List<ProviderBinding> bindings) {
        List<ProviderBindingId> ids = new ArrayList<>();
        bindings.forEach(binding -> ids.add(binding.id()));
        return List.copyOf(ids);
    }
}
