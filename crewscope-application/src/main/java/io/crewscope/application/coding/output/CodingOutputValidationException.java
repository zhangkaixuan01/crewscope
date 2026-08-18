package io.crewscope.application.coding.output;

/** Fail-closed rejection of an untrusted Coding Specialist structured output. */
public final class CodingOutputValidationException extends IllegalArgumentException {

    private final String field;

    public CodingOutputValidationException(String field, String message) {
        super(field + ": " + message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
