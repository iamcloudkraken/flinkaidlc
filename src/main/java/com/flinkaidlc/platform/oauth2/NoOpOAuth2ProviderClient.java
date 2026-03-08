package com.flinkaidlc.platform.oauth2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op implementation of {@link OAuth2ProviderClient} for testing and local development.
 * All operations succeed silently without contacting any external provider.
 */
public class NoOpOAuth2ProviderClient implements OAuth2ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(NoOpOAuth2ProviderClient.class);

    @Override
    public void registerClient(String fid, String secret) {
        log.info("[NoOp] OAuth2 registerClient called for fid={} — no-op", fid);
    }

    @Override
    public void deleteClient(String fid) {
        log.info("[NoOp] OAuth2 deleteClient called for fid={} — no-op", fid);
    }
}
