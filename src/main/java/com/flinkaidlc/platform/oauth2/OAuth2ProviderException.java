package com.flinkaidlc.platform.oauth2;

public class OAuth2ProviderException extends RuntimeException {

    public OAuth2ProviderException(String message) {
        super(message);
    }

    public OAuth2ProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
