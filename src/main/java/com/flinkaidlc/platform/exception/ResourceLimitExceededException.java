package com.flinkaidlc.platform.exception;

public class ResourceLimitExceededException extends RuntimeException {

    public ResourceLimitExceededException(String message) {
        super(message);
    }

    public ResourceLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
