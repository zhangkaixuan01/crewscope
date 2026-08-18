package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Bounded module and exact test selectors accepted in addition to a command's fixed argv. */
public record CommandSelectorPolicy(
        List<String> allowedModules,
        int maxModuleSelectors,
        int maxTestSelectors,
        int maxSelectorLength) {

    private static final int MAX_ALLOWED_MODULES = 200;
    private static final int MAX_SELECTORS = 20;
    private static final int MAX_SELECTOR_LENGTH = 256;
    private static final String TEST_SELECTOR_REGEX =
            "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*"
                    + "[A-Za-z_$][A-Za-z0-9_$]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?";

    public CommandSelectorPolicy {
        Collection<String> supplied = Objects.requireNonNull(allowedModules, "allowedModules");
        if (supplied.size() > MAX_ALLOWED_MODULES
                || maxModuleSelectors < 0
                || maxModuleSelectors > MAX_SELECTORS
                || maxTestSelectors < 0
                || maxTestSelectors > MAX_SELECTORS
                || maxSelectorLength < 1
                || maxSelectorLength > MAX_SELECTOR_LENGTH) {
            throw new DomainValidationException(
                    "buildCommand.selectorPolicy", "selector limits exceed the supported bounds");
        }
        TreeSet<String> normalized = new TreeSet<>();
        supplied.forEach(module -> {
            CodingTargetAllowedPaths canonical = CodingTargetAllowedPaths.of(module);
            if (canonical.values().size() != 1 || !canonical.values().get(0).equals(module)) {
                throw new DomainValidationException(
                        "buildCommand.selectorPolicy.allowedModules",
                        "must contain canonical repository-relative module paths");
            }
            normalized.add(module);
        });
        if ((maxModuleSelectors == 0) != normalized.isEmpty()) {
            throw new DomainValidationException(
                    "buildCommand.selectorPolicy.maxModuleSelectors",
                    "must be zero exactly when module selection is disabled");
        }
        allowedModules = List.copyOf(normalized);
    }

    public static CommandSelectorPolicy none() {
        return new CommandSelectorPolicy(List.of(), 0, 0, MAX_SELECTOR_LENGTH);
    }

    public boolean allowsModules(Collection<String> modules) {
        List<String> required = List.copyOf(Objects.requireNonNull(modules, "modules"));
        return required.size() <= maxModuleSelectors
                && required.stream().allMatch(module ->
                        module != null
                                && module.length() <= maxSelectorLength
                                && allowedModules.contains(module));
    }

    public boolean allowsTests(Collection<String> tests) {
        List<String> required = List.copyOf(Objects.requireNonNull(tests, "tests"));
        return required.size() <= maxTestSelectors
                && required.stream().allMatch(test ->
                        test != null
                                && test.length() <= maxSelectorLength
                                && test.matches(TEST_SELECTOR_REGEX));
    }
}
