package io.crewscope.application.coding.output;

/** Shared lexical constraints for untrusted Coding Specialist structured output. */
final class CodingOutputPatterns {

    static final String VERSION_ONE = "1";
    static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    static final String SHA_256 = "[0-9a-f]{64}";
    static final String REPOSITORY_PATH =
            "(?!/)(?!\\\\)(?![A-Za-z]:)(?!.*(?:^|/)\\.{1,2}(?:/|$))(?!.*//)(?!.*\\\\)[^\\x00-\\x1f]{1,1024}";

    private CodingOutputPatterns() {}
}
