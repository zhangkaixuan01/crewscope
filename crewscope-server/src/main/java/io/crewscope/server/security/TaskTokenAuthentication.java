package io.crewscope.server.security;

import io.crewscope.application.task.TaskTokenExecutionContext;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security identity representing a fully server-verified Task Token. */
public final class TaskTokenAuthentication extends AbstractAuthenticationToken {

    private final TaskTokenExecutionContext context;

    public TaskTokenAuthentication(TaskTokenExecutionContext context) {
        super(List.of(new SimpleGrantedAuthority("TASK_RUNTIME")));
        this.context = Objects.requireNonNull(context, "context");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "[REDACTED]";
    }

    @Override
    public TaskTokenExecutionContext getPrincipal() {
        return context;
    }

    @Override
    public String getName() {
        return context.scope().executionPrincipal().principalId().toString();
    }
}
