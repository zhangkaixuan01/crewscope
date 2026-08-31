package io.crewscope.agentscope;

import io.agentscope.core.model.ToolSchema;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Provider-neutral validation for every Tool name crossing the model API boundary. */
public final class ModelToolNamePolicy {

    /** OpenAI, DeepSeek and their compatible APIs share this name shape and 64-char budget. */
    private static final Pattern COMPATIBLE_NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private ModelToolNamePolicy() {}

    public static Set<String> requireCompatibleNames(Set<String> names) {
        Set<String> required = Set.copyOf(Objects.requireNonNull(names, "names"));
        if (required.stream().anyMatch(name -> !COMPATIBLE_NAME.matcher(name).matches())) {
            throw invalidName();
        }
        return required;
    }

    /**
     * Converts stable dotted policy keys to model-facing aliases and rejects alias collisions.
     * Already compatible keys retain their exact name.
     */
    public static Set<String> runtimeAliases(Set<String> canonicalNames) {
        Set<String> canonical = Set.copyOf(Objects.requireNonNull(
                canonicalNames, "canonicalNames"));
        Set<String> aliases = new LinkedHashSet<>();
        for (String name : canonical) {
            String alias = Objects.requireNonNull(name, "canonical Tool name").replace('.', '_');
            if (!aliases.add(alias)) {
                throw invalidName();
            }
        }
        return requireCompatibleNames(aliases);
    }

    public static void requireCompatibleSchemas(List<ToolSchema> schemas) {
        // AgentScope 2.0 uses null for model calls that expose no Tools, including memory
        // compaction, consolidation and subagent-spec generation. Preserve that native contract;
        // only validate a concrete Tool surface before it crosses the provider boundary.
        if (schemas == null) {
            return;
        }
        List<ToolSchema> required = List.copyOf(schemas);
        Set<String> names = new HashSet<>();
        for (ToolSchema schema : required) {
            String name = Objects.requireNonNull(schema, "tool schema").getName();
            if (name == null
                    || !COMPATIBLE_NAME.matcher(name).matches()
                    || !names.add(name)) {
                throw invalidName();
            }
        }
    }

    private static IllegalArgumentException invalidName() {
        return new IllegalArgumentException(
                "Model-facing Tool names must be unique OpenAI-compatible identifiers");
    }
}
