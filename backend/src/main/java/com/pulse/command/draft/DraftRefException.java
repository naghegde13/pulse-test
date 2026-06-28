package com.pulse.command.draft;

public class DraftRefException extends IllegalArgumentException {

    private final String errorCode;

    public DraftRefException(String errorCode, String message) {
        super(errorCode + " — " + message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
