package io.crewscope.server.api;

import io.crewscope.application.model.ModelCatalogItemView;
import io.crewscope.application.model.ModelConnectionApplicationService;
import io.crewscope.application.team.TeamAccessContext;
import io.crewscope.domain.model.ModelCatalogEntry;
import io.crewscope.domain.model.ModelPriceRevision;
import io.crewscope.domain.model.ModelProviderDefinition;
import io.crewscope.domain.model.ModelProviderKey;
import io.crewscope.domain.shared.id.OrganizationId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Public-safe, Organization-scoped model provider and versioned catalog query boundary. */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/model-providers")
public final class ModelCatalogController {

    private final ModelConnectionApplicationService service;
    private final TeamRequestIdentityResolver identityResolver;

    public ModelCatalogController(
            ModelConnectionApplicationService service,
            TeamRequestIdentityResolver identityResolver) {
        this.service = service;
        this.identityResolver = identityResolver;
    }

    @GetMapping
    public Mono<ResponseEntity<ModelProviderListResponse>> listProviders(
            @PathVariable String organizationId,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.listProviders(access, organization, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ModelProviderListResponse.from(values)));
    }

    @GetMapping("/{providerKey}/catalog")
    public Mono<ResponseEntity<ModelCatalogListResponse>> listCatalog(
            @PathVariable String organizationId,
            @PathVariable String providerKey,
            @RequestParam(defaultValue = "0") @Min(0) int offset,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit,
            Authentication authentication,
            ServerWebExchange exchange) {
        OrganizationId organization = organizationId(organizationId);
        ModelProviderKey provider = providerKey(providerKey);
        int pageSize = ApiPagination.limit(limit);
        return query(
                        authentication,
                        organization,
                        exchange,
                        access -> service.listCatalog(access, organization, provider, offset, pageSize))
                .map(values -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(ModelCatalogListResponse.from(values)));
    }

    private <T> Mono<T> query(
            Authentication authentication,
            OrganizationId organizationId,
            ServerWebExchange exchange,
            Function<TeamAccessContext, T> action) {
        return identityResolver
                .resolve(authentication, organizationId, ApiCorrelationIds.resolve(exchange))
                .flatMap(access -> blocking(() -> action.apply(access)));
    }

    private static <T> Mono<T> blocking(Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static OrganizationId organizationId(String value) {
        try {
            return OrganizationId.from(value);
        } catch (IllegalArgumentException failure) {
            throw invalidField("organizationId");
        }
    }

    private static ModelProviderKey providerKey(String value) {
        try {
            return new ModelProviderKey(value);
        } catch (RuntimeException failure) {
            throw invalidField("providerKey");
        }
    }

    private static ApiRequestException invalidField(String field) {
        return new ApiRequestException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "invalid_request",
                "Request contains an invalid model catalog field",
                Map.of("field", field));
    }

    public record ModelProviderListResponse(List<ModelProviderResponse> items) {
        static ModelProviderListResponse from(List<ModelProviderDefinition> values) {
            return new ModelProviderListResponse(values.stream()
                    .map(ModelProviderResponse::from)
                    .toList());
        }
    }

    /** Endpoint and AgentScope adapter keys deliberately remain server-internal. */
    public record ModelProviderResponse(
            String key,
            String displayName,
            List<String> availableRegions,
            String retentionMode,
            Long maximumRetentionSeconds,
            String trainingUsagePolicy,
            String status,
            long version) {
        static ModelProviderResponse from(ModelProviderDefinition provider) {
            return new ModelProviderResponse(
                    provider.providerKey().toString(),
                    provider.displayName(),
                    provider.availableRegions().stream().map(Object::toString).sorted().toList(),
                    provider.dataPolicy().retentionMode().name(),
                    provider.dataPolicy().maximumRetention().map(java.time.Duration::getSeconds).orElse(null),
                    provider.dataPolicy().trainingUsagePolicy().name(),
                    provider.status().name(),
                    provider.lifecycleVersion());
        }
    }

    public record ModelCatalogListResponse(List<ModelCatalogResponse> items) {
        static ModelCatalogListResponse from(List<ModelCatalogItemView> values) {
            return new ModelCatalogListResponse(values.stream()
                    .map(ModelCatalogResponse::from)
                    .toList());
        }
    }

    public record ModelCatalogResponse(
            String id,
            String providerKey,
            String modelId,
            long catalogRevision,
            String modelRevision,
            String displayName,
            long contextWindowTokens,
            long maximumOutputTokens,
            List<String> capabilities,
            List<String> availableRegions,
            String status,
            long version,
            ModelPriceResponse effectivePrice) {
        static ModelCatalogResponse from(ModelCatalogItemView view) {
            ModelCatalogEntry catalog = view.catalog();
            return new ModelCatalogResponse(
                    catalog.id().toString(),
                    catalog.providerKey().toString(),
                    catalog.modelId().toString(),
                    catalog.catalogRevision().value(),
                    catalog.modelRevision().toString(),
                    catalog.displayName(),
                    catalog.contextWindowTokens(),
                    catalog.maximumOutputTokens(),
                    catalog.capabilities().stream().map(Object::toString).sorted().toList(),
                    catalog.availableRegions().stream().map(Object::toString).sorted().toList(),
                    catalog.status().name(),
                    catalog.lifecycleVersion(),
                    view.effectivePrice().map(ModelPriceResponse::from).orElse(null));
        }
    }

    public record ModelPriceResponse(
            long revision,
            String effectiveFrom,
            String inputPerMillionTokens,
            String outputPerMillionTokens,
            String cachedInputPerMillionTokens,
            String currencyCode) {
        static ModelPriceResponse from(ModelPriceRevision price) {
            return new ModelPriceResponse(
                    price.revision(),
                    price.effectiveFrom().toString(),
                    price.tokenPrice().inputPerMillionTokens().toPlainString(),
                    price.tokenPrice().outputPerMillionTokens().toPlainString(),
                    price.tokenPrice().cachedInputPerMillionTokens()
                            .map(java.math.BigDecimal::toPlainString)
                            .orElse(null),
                    price.tokenPrice().currencyCode());
        }
    }
}
