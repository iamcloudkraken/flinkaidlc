package com.flinkaidlc.platform.exception;

public class SlugAlreadyInUseException extends RuntimeException {

    public SlugAlreadyInUseException(String slug) {
        super("Slug '" + slug + "' is already in use");
    }
}
