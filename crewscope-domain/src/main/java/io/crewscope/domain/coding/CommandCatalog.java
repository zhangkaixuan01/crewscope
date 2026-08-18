package io.crewscope.domain.coding;

import io.crewscope.domain.shared.error.DomainValidationException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable command slots. Runtime overlays can only remove complete slots. */
public record CommandCatalog(Map<CommandKind, BuildCommand> commands) {

    public CommandCatalog {
        Map<CommandKind, BuildCommand> supplied = Objects.requireNonNull(commands, "commands");
        if (supplied.size() > CommandKind.values().length) {
            throw new DomainValidationException("commandCatalog.commands", "contains too many commands");
        }
        EnumMap<CommandKind, BuildCommand> copy = new EnumMap<>(CommandKind.class);
        supplied.forEach((kind, command) -> copy.put(
                Objects.requireNonNull(kind, "commandKind"),
                Objects.requireNonNull(command, "buildCommand")));
        if (new HashSet<>(copy.values().stream().map(BuildCommand::toolKey).toList()).size()
                != copy.size()) {
            throw new DomainValidationException(
                    "commandCatalog.toolKey", "each command slot must have a unique Tool key");
        }
        commands = Collections.unmodifiableMap(copy);
    }

    public static CommandCatalog of(CommandKind kind, BuildCommand command) {
        return new CommandCatalog(Map.of(kind, command));
    }

    public Set<String> toolKeys() {
        return commands.values().stream()
                .map(BuildCommand::toolKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    public int maximumCommandTimeoutSeconds() {
        return commands.values().stream()
                .mapToInt(BuildCommand::maxTimeoutSeconds)
                .max()
                .orElse(0);
    }

    void validateFor(BuildTool tool) {
        commands.values().forEach(command -> command.validateFor(tool));
    }

    /** True only when the candidate retains unchanged entries from this catalog. */
    public boolean containsUnchanged(CommandCatalog candidate) {
        CommandCatalog required = Objects.requireNonNull(candidate, "candidate");
        return required.commands.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(commands.get(entry.getKey())));
    }
}
