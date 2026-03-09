package com.flinkaidlc.platform.oauth2;

/**
 * Abstraction over the OAuth2 provider admin API.
 * Allows swapping between Keycloak and stub implementations.
 */
public interface OAuth2ProviderClient {

    /**
     * Registers a new OAuth2 client credentials client with the provider.
     *
     * @param fid    the client ID (Functional ID) to register
     * @param secret the client secret to associate with the client
     * @throws OAuth2ProviderException if the provider is unavailable or returns an error
     */
    void registerClient(String fid, String secret);

    /**
     * Deletes a previously registered OAuth2 client (compensating action on failure).
     *
     * @param fid the client ID to delete
     */
    void deleteClient(String fid);
}
