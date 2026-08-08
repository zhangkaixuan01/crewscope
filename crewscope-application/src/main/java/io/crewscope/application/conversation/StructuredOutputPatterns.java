package io.crewscope.application.conversation;

/** Compile-time patterns shared by versioned Conversation structured-output records. */
public final class StructuredOutputPatterns {

    public static final String VERSION_ONE = "1";
    public static final String CANONICAL_UUID =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    public static final String FIELD_KEY = "[a-z][a-z0-9_]{0,63}";

    private StructuredOutputPatterns() {}
}
