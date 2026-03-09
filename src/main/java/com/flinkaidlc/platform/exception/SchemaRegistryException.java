package com.flinkaidlc.platform.exception;

public class SchemaRegistryException extends RuntimeException {

    public SchemaRegistryException(String message) {
        super(message);
    }

    public SchemaRegistryException(String message, Throwable cause) {
        super(message, cause);
    }
}
