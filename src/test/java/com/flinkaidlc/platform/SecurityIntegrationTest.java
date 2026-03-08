package com.flinkaidlc.platform;

import com.flinkaidlc.platform.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void postTenantsIsPermittedWithoutAuth() {
        // POST /api/v1/tenants should NOT return 401 — endpoint doesn't exist yet so we get 404 or similar
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/tenants",
                HttpMethod.POST,
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAnyApiEndpointReturns401WithoutJwt() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/pipelines",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getAnyApiEndpointWithValidJwtReturnsNotUnauthorized() {
        // With our TestSecurityConfig providing a mock JwtDecoder, a valid token is accepted
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("test-token");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/pipelines",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        // Should not be 401 — endpoint doesn't exist yet, so 404 is expected
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
