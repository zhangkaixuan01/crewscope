package io.crewscope.server.observability;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

/** Bridges the Reactor correlation value into the SLF4J MDC for each signal. */
final class CorrelationIdThreadLocalAccessor implements ThreadLocalAccessor<String> {

    static final String KEY = CorrelationIdThreadLocalAccessor.class.getName();
    static final String MDC_KEY = "correlationId";

    private static final CorrelationIdThreadLocalAccessor INSTANCE =
            new CorrelationIdThreadLocalAccessor();

    static synchronized void register() {
        boolean registered = ContextRegistry.getInstance().getThreadLocalAccessors().stream()
                .anyMatch(accessor -> KEY.equals(accessor.key()));
        if (!registered) {
            ContextRegistry.getInstance().registerThreadLocalAccessor(INSTANCE);
        }
    }

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return MDC.get(MDC_KEY);
    }

    @Override
    public void setValue(String value) {
        if (value == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, value);
        }
    }

    @Override
    public void setValue() {
        MDC.remove(MDC_KEY);
    }
}
