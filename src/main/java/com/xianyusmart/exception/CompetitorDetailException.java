package com.xianyusmart.exception;

/** Typed platform failure; callers must not infer retry eligibility from translated messages. */
public class CompetitorDetailException extends IllegalStateException {
    private final boolean validationRequired;
    private final String reason;

    public CompetitorDetailException(String message, boolean validationRequired, String reason) {
        super(message);
        this.validationRequired = validationRequired;
        this.reason = reason;
    }

    public boolean isValidationRequired() { return validationRequired; }
    public String getReason() { return reason; }
}
