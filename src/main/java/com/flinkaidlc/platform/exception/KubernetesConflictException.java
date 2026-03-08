package com.flinkaidlc.platform.exception;

public class KubernetesConflictException extends RuntimeException {

    public KubernetesConflictException(String message) {
        super(message);
    }

    public KubernetesConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
