package com.flinkaidlc.platform.oauth2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Keycloak Admin REST API implementation of {@link OAuth2ProviderClient}.
 *
 * <p>Registers OAuth2 client credentials clients via
 * {@code POST /admin/realms/{realm}/clients} using a privileged admin token obtained
 * via client credentials flow against the configured admin client.
 */
public class KeycloakOAuth2ProviderClient implements OAuth2ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakOAuth2ProviderClient.class);

    private final String adminUrl;
    private final String realm;
    private final String adminClientId;
    private final String adminClientSecret;
    private final RestClient restClient;

    public KeycloakOAuth2ProviderClient(
        String adminUrl,
        String realm,
        String adminClientId,
        String adminClientSecret,
        RestClient.Builder restClientBuilder
    ) {
        this.adminUrl = adminUrl;
        this.realm = realm;
        this.adminClientId = adminClientId;
        this.adminClientSecret = adminClientSecret;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void registerClient(String fid, String secret) {
        String adminToken = fetchAdminToken();

        Map<String, Object> clientRepresentation = Map.of(
            "clientId", fid,
            "secret", secret,
            "enabled", true,
            "serviceAccountsEnabled", true,
            "directAccessGrantsEnabled", false,
            "publicClient", false,
            "protocol", "openid-connect",
            "attributes", Map.of(
                "access.token.lifespan", "3600"
            ),
            "defaultClientScopes", List.of("web-origins", "profile", "roles", "email"),
            "optionalClientScopes", List.of("offline_access", "address", "phone")
        );

        try {
            restClient.post()
                .uri(adminUrl + "/admin/realms/" + realm + "/clients")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(clientRepresentation)
                .retrieve()
                .toBodilessEntity();
            log.info("Registered OAuth2 client fid={} in Keycloak realm={}", fid, realm);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new OAuth2ProviderException("OAuth2 client already exists for fid: " + fid, e);
            }
            throw new OAuth2ProviderException("Failed to register OAuth2 client for fid: " + fid + " — " + e.getMessage(), e);
        } catch (Exception e) {
            throw new OAuth2ProviderException("OAuth2 provider unavailable when registering fid: " + fid, e);
        }
    }

    @Override
    public void deleteClient(String fid) {
        try {
            String adminToken = fetchAdminToken();

            // First fetch the internal Keycloak client UUID by clientId
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clients = restClient.get()
                .uri(adminUrl + "/admin/realms/" + realm + "/clients?clientId=" + fid)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .body(List.class);

            if (clients == null || clients.isEmpty()) {
                log.warn("No Keycloak client found for fid={} during compensating delete", fid);
                return;
            }

            String internalId = (String) clients.get(0).get("id");
            restClient.delete()
                .uri(adminUrl + "/admin/realms/" + realm + "/clients/" + internalId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .retrieve()
                .toBodilessEntity();
            log.info("Deleted OAuth2 client fid={} from Keycloak realm={}", fid, realm);
        } catch (Exception e) {
            // Compensating deletes are best-effort; log and continue
            log.error("Failed to delete OAuth2 client fid={} during compensating delete: {}", fid, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private String fetchAdminToken() {
        try {
            // Use MultiValueMap so Spring's FormHttpMessageConverter properly URL-encodes
            // each field value — prevents injection if clientId/secret contain '&' or '='.
            MultiValueMap<String, String> formBody = new LinkedMultiValueMap<>();
            formBody.add("grant_type", "client_credentials");
            formBody.add("client_id", adminClientId);
            formBody.add("client_secret", adminClientSecret);

            Map<String, Object> tokenResponse = restClient.post()
                .uri(adminUrl + "/realms/" + realm + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(formBody)
                .retrieve()
                .body(Map.class);

            if (tokenResponse == null || !tokenResponse.containsKey("access_token")) {
                throw new OAuth2ProviderException("Failed to obtain admin token from Keycloak: empty response");
            }
            return (String) tokenResponse.get("access_token");
        } catch (OAuth2ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuth2ProviderException("OAuth2 provider unavailable when fetching admin token", e);
        }
    }
}
