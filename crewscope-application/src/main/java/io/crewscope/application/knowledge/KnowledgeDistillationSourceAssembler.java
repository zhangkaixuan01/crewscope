package io.crewscope.application.knowledge;

import io.crewscope.application.task.TaskEvent;
import io.crewscope.application.task.TaskEventPage;
import io.crewscope.application.task.TaskEventQuery;
import io.crewscope.application.task.TaskEventRepository;
import io.crewscope.domain.shared.error.DomainValidationException;
import io.crewscope.domain.shared.event.EventType;
import io.crewscope.domain.task.Task;
import io.crewscope.domain.task.TaskExecution;
import io.crewscope.application.task.TaskEventCursor;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Renders the sanitized durable event stream of one completed Task execution attempt into
 * the distillation source text (D7). The stream is already the public-safe projection:
 * every payload field was sanitized at write time, so this class only whitelists which
 * events become model input and enforces the hard length ceiling. The assembled source
 * as a whole is rejected explicitly when it exceeds the ceiling — never silently
 * truncated (S01 §3.5); individual payloads render under their own fixed per-kind caps
 * (text 10k, reasoning summary 2k), which is rendering, not truncation of the source.
 */
public final class KnowledgeDistillationSourceAssembler {

    /** 128k characters; the ceiling is the contract, not a buffer size. */
    public static final int MAX_SOURCE_TEXT_LENGTH = 131_072;

    private static final int PAGE_SIZE = 100;
    private static final EventType AGENT_RUN_EVENT = EventType.from("AGENT_RUN_EVENT_RECORDED");
    /** Only these sanitized event kinds carry knowledge-worthy signal; progress and chatter do not. */
    private static final Set<String> RENDERED_KINDS =
            Set.of("TEXT_DELTA", "STRUCTURED_OUTPUT", "THINKING_SUMMARY", "COMPLETED");

    private final TaskEventRepository taskEvents;

    public KnowledgeDistillationSourceAssembler(TaskEventRepository taskEvents) {
        this.taskEvents = Objects.requireNonNull(taskEvents, "taskEvents");
    }

    /** Assembles the source text of the current attempt; rejects oversize with 422 semantics. */
    public String assemble(Task task, TaskExecution execution) {
        Task requiredTask = Objects.requireNonNull(task, "task");
        TaskExecution requiredExecution = Objects.requireNonNull(execution, "execution");
        StringBuilder source = new StringBuilder();
        Optional<TaskEventCursor> cursor = Optional.empty();
        while (true) {
            TaskEventPage page = taskEvents.findPage(
                    new TaskEventQuery(requiredTask.scope(), requiredTask.id(), cursor, PAGE_SIZE),
                    false);
            for (TaskEvent event : page.events()) {
                if (!requiredExecution.id().equals(
                        event.context().taskExecutionId().orElse(null))) {
                    continue;
                }
                if (!AGENT_RUN_EVENT.equals(event.envelope().eventType())) {
                    continue;
                }
                render(event, source);
                if (source.length() > MAX_SOURCE_TEXT_LENGTH) {
                    throw oversized();
                }
            }
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }
        return source.toString();
    }

    private static void render(TaskEvent event, StringBuilder source) {
        Object payload = event.envelope().payload();
        if (!(payload instanceof java.util.Map<?, ?> fields)) {
            return;
        }
        String kind = text(fields.get("eventKind"));
        if (kind == null || !RENDERED_KINDS.contains(kind)) {
            return;
        }
        String safeText = text(fields.get("safeText"));
        switch (kind) {
            // Streaming deltas keep their original delimiters so the transcript stays readable.
            case "TEXT_DELTA" -> {
                if (safeText != null) {
                    source.append(safeText);
                }
            }
            case "STRUCTURED_OUTPUT" -> {
                if (safeText != null) {
                    source.append("\n[structured output]\n").append(safeText.strip()).append('\n');
                }
            }
            case "THINKING_SUMMARY" -> {
                if (safeText != null) {
                    String summary = safeText.strip();
                    if (summary.length() > 2_000) {
                        summary = summary.substring(0, 2_000);
                    }
                    source.append("\n[reasoning] ").append(summary).append('\n');
                }
            }
            case "COMPLETED" -> source.append("\n[completed]\n");
            default -> {
                // unreachable: the whitelist above is exhaustive
            }
        }
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static DomainValidationException oversized() {
        return new DomainValidationException(
                "distillation.sourceText",
                "must not exceed %d characters".formatted(MAX_SOURCE_TEXT_LENGTH));
    }
}
