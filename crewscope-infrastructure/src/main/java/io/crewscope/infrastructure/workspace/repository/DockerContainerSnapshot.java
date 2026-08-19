package io.crewscope.infrastructure.workspace.repository;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Internal projection of Docker inspect facts used to fail closed before reuse or cleanup. */
record DockerContainerSnapshot(JsonNode inspect) {

    DockerContainerSnapshot {
        inspect = Objects.requireNonNull(inspect, "inspect");
    }

    String id() {
        return text("Id");
    }

    String name() {
        String value = text("Name");
        return value.startsWith("/") ? value.substring(1) : value;
    }

    boolean running() {
        return inspect.path("State").path("Running").asBoolean(false);
    }

    String configuredImage() {
        return textAt("Config", "Image");
    }

    String configuredUser() {
        return textAt("Config", "User");
    }

    Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        JsonNode configured = inspect.path("Config").path("Labels");
        if (configured.isObject()) {
            configured.properties().forEach(entry ->
                    labels.put(entry.getKey(), entry.getValue().asText()));
        }
        return Map.copyOf(labels);
    }

    String networkMode() {
        return textAt("HostConfig", "NetworkMode");
    }

    boolean readOnlyRootFilesystem() {
        return inspect.path("HostConfig").path("ReadonlyRootfs").asBoolean(false);
    }

    long memoryBytes() {
        return inspect.path("HostConfig").path("Memory").asLong(-1);
    }

    long nanoCpus() {
        return inspect.path("HostConfig").path("NanoCpus").asLong(-1);
    }

    long pidsLimit() {
        return inspect.path("HostConfig").path("PidsLimit").asLong(-1);
    }

    boolean dropsAllCapabilities() {
        JsonNode values = inspect.path("HostConfig").path("CapDrop");
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            if ("ALL".equalsIgnoreCase(value.asText())) {
                return true;
            }
        }
        return false;
    }

    boolean preventsPrivilegeEscalation() {
        JsonNode values = inspect.path("HostConfig").path("SecurityOpt");
        if (!values.isArray()) {
            return false;
        }
        for (JsonNode value : values) {
            String option = value.asText();
            if ("no-new-privileges".equals(option)
                    || "no-new-privileges:true".equals(option)) {
                return true;
            }
        }
        return false;
    }

    boolean hasReadWriteBindMount(Path source, String destination) {
        Path expected = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        for (JsonNode mount : inspect.path("Mounts")) {
            if (!"bind".equals(mount.path("Type").asText())
                    || !destination.equals(mount.path("Destination").asText())
                    || !mount.path("RW").asBoolean(false)) {
                continue;
            }
            try {
                if (Path.of(mount.path("Source").asText())
                        .toAbsolutePath()
                        .normalize()
                        .equals(expected)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    Optional<String> environment(String name) {
        String prefix = Objects.requireNonNull(name, "name") + "=";
        for (JsonNode value : inspect.path("Config").path("Env")) {
            String entry = value.asText();
            if (entry.startsWith(prefix)) {
                return Optional.of(entry.substring(prefix.length()));
            }
        }
        return Optional.empty();
    }

    private String text(String field) {
        return inspect.path(field).asText("");
    }

    private String textAt(String parent, String field) {
        return inspect.path(parent).path(field).asText("");
    }
}
