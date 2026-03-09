package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.AbstractIntegrationTest;
import com.flinkaidlc.platform.config.TestSecurityConfig;
import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.domain.TenantStatus;
import com.flinkaidlc.platform.k8s.ITenantNamespaceProvisioner;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderClient;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderException;
import com.flinkaidlc.platform.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static com.flinkaidlc.platform.config.TestSecurityConfig.TEST_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link TenantController}.
 *
 * <p>Uses Testcontainers PostgreSQL (from {@link AbstractIntegrationTest}),
 * with mocked {@link ITenantNamespaceProvisioner} and {@link OAuth2ProviderClient}
 * to avoid needing a real K8s cluster or Keycloak.
 */
class TenantControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TenantRepository tenantRepository;

    @MockBean
    private ITenantNamespaceProvisioner provisioner;

    @MockBean
    private OAuth2ProviderClient oauth2Client;

    @AfterEach
    void cleanUp() {
        tenantRepository.deleteAll();
    }

    // ---- POST /api/v1/tenants ----

    @Test
    void postTenants_validRequest_returns201WithFidAndSecret() {
        String body = """
            {
              "name": "Acme Analytics",
              "slug": "acme-it",
              "contactEmail": "admin@acme.com",
              "maxPipelines": 10,
              "maxTotalParallelism": 50
            }
            """;

        ResponseEntity<OnboardTenantResponse> response = restTemplate.postForEntity(
            "/api/v1/tenants",
            requestEntity(body, false),
            OnboardTenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        OnboardTenantResponse resp = response.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.slug()).isEqualTo("acme-it");
        assertThat(resp.fid()).isNotBlank();
        assertThat(resp.fidSecret()).isNotBlank();
        assertThat(resp.namespaceProvisioned()).isTrue();
        assertThat(resp.tenantId()).isNotNull();

        verify(provisioner).provision(eq("acme-it"), eq(10), eq(50));
        verify(oauth2Client).registerClient(anyString(), anyString());
    }

    @Test
    void postTenants_duplicateSlug_returns400() {
        String body = """
            {
              "name": "Acme Analytics",
              "slug": "acme-dup",
              "contactEmail": "admin@acme.com",
              "maxPipelines": 10,
              "maxTotalParallelism": 50
            }
            """;

        // First registration succeeds
        restTemplate.postForEntity("/api/v1/tenants", requestEntity(body, false), String.class);

        // Reset mocks so provisioner/oauth2 don't fail on second call
        reset(provisioner, oauth2Client);

        // Second registration with same slug should fail
        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/v1/tenants",
            requestEntity(body, false),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("acme-dup");
    }

    @Test
    void postTenants_invalidSlug_returns400() {
        String body = """
            {
              "name": "Bad Slug Corp",
              "slug": "BAD_SLUG!",
              "contactEmail": "admin@bad.com",
              "maxPipelines": 5,
              "maxTotalParallelism": 10
            }
            """;

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/v1/tenants",
            requestEntity(body, false),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(provisioner, oauth2Client);
    }

    @Test
    void postTenants_k8sProvisioningFails_returns503AndRollsBack() {
        doThrow(new RuntimeException("K8s unavailable"))
            .when(provisioner).provision(anyString(), anyInt(), anyInt());

        String body = """
            {
              "name": "Failing Corp",
              "slug": "failing",
              "contactEmail": "admin@failing.com",
              "maxPipelines": 5,
              "maxTotalParallelism": 10
            }
            """;

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/v1/tenants",
            requestEntity(body, false),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        // Confirm no orphaned tenant in DB
        assertThat(tenantRepository.findBySlug("failing")).isEmpty();
    }

    @Test
    void postTenants_oauth2Fails_returns503AndRollsBack() {
        doThrow(new OAuth2ProviderException("Keycloak down"))
            .when(oauth2Client).registerClient(anyString(), anyString());

        String body = """
            {
              "name": "OAuth Fail Corp",
              "slug": "oauth-fail",
              "contactEmail": "admin@fail.com",
              "maxPipelines": 5,
              "maxTotalParallelism": 10
            }
            """;

        ResponseEntity<String> response = restTemplate.postForEntity(
            "/api/v1/tenants",
            requestEntity(body, false),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(tenantRepository.findBySlug("oauth-fail")).isEmpty();
        // K8s compensating deprovision should be attempted
        verify(provisioner).deprovision("oauth-fail");
    }

    // ---- GET /api/v1/tenants/{tenantId} ----

    @Test
    void getTenant_authenticatedMatchingTenant_returns200() {
        Tenant tenant = createTenant(TEST_TENANT_ID, "test-tenant");

        ResponseEntity<TenantResponse> response = restTemplate.exchange(
            "/api/v1/tenants/" + TEST_TENANT_ID,
            HttpMethod.GET,
            requestEntity(null, true),
            TenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantResponse resp = response.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.tenantId()).isEqualTo(TEST_TENANT_ID);
        assertThat(resp.slug()).isEqualTo("test-tenant");
        assertThat(resp.usedPipelines()).isEqualTo(0L);
        assertThat(resp.usedParallelism()).isEqualTo(0L);
    }

    @Test
    void getTenant_crossTenantAccess_returns403() {
        UUID otherTenantId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/v1/tenants/" + otherTenantId,
            HttpMethod.GET,
            requestEntity(null, true),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getTenant_notFound_returns404() {
        // Use the test tenant id in the JWT but no tenant exists in DB
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/v1/tenants/" + TEST_TENANT_ID,
            HttpMethod.GET,
            requestEntity(null, true),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getTenant_unauthenticated_returns401() {
        ResponseEntity<String> response = restTemplate.exchange(
            "/api/v1/tenants/" + TEST_TENANT_ID,
            HttpMethod.GET,
            requestEntity(null, false),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---- PUT /api/v1/tenants/{tenantId} ----

    @Test
    void putTenant_validRequest_updates200AndPatchesK8sOnQuotaChange() {
        createTenant(TEST_TENANT_ID, "test-tenant");

        String body = """
            {
              "name": "Updated Name",
              "contactEmail": "updated@test.com",
              "maxPipelines": 20,
              "maxTotalParallelism": 100
            }
            """;

        ResponseEntity<TenantResponse> response = restTemplate.exchange(
            "/api/v1/tenants/" + TEST_TENANT_ID,
            HttpMethod.PUT,
            requestEntity(body, true),
            TenantResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantResponse resp = response.getBody();
        assertThat(resp).isNotNull();
        assertThat(resp.name()).isEqualTo("Updated Name");
        assertThat(resp.maxPipelines()).isEqualTo(20);
        assertThat(resp.maxTotalParallelism()).isEqualTo(100);

        verify(provisioner).patchResourceQuota(eq("test-tenant"), eq(20), eq(100));
    }

    @Test
    void putTenant_crossTenantAccess_returns403() {
        UUID otherTenantId = UUID.randomUUID();
        String body = """
            {
              "name": "Hacked",
              "contactEmail": "hacker@evil.com",
              "maxPipelines": 100,
              "maxTotalParallelism": 1000
            }
            """;

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/v1/tenants/" + otherTenantId,
            HttpMethod.PUT,
            requestEntity(body, true),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- DELETE /api/v1/tenants/{tenantId} ----

    @Test
    void deleteTenant_validRequest_returns204AndMarksDeleted() {
        createTenant(TEST_TENANT_ID, "test-tenant");

        ResponseEntity<Void> response = restTemplate.exchange(
            "/api/v1/tenants/" + TEST_TENANT_ID,
            HttpMethod.DELETE,
            requestEntity(null, true),
            Void.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(provisioner).deprovision("test-tenant");

        // Tenant should be marked DELETED in DB
        Tenant updated = tenantRepository.findById(TEST_TENANT_ID).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(TenantStatus.DELETED);
    }

    @Test
    void deleteTenant_crossTenantAccess_returns403() {
        UUID otherTenantId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
            "/api/v1/tenants/" + otherTenantId,
            HttpMethod.DELETE,
            requestEntity(null, true),
            String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ----

    private HttpEntity<?> requestEntity(String body, boolean withAuth) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withAuth) {
            headers.setBearerAuth("test-token");
        }
        return new HttpEntity<>(body, headers);
    }

    /**
     * Persists a tenant directly via repository, bypassing K8s/OAuth2 provisioning.
     * Uses reflection to set the tenant_id (which is generated by DB).
     */
    private Tenant createTenant(UUID tenantId, String slug) {
        Tenant tenant = new Tenant();
        tenant.setSlug(slug);
        tenant.setName("Test Tenant");
        tenant.setContactEmail("test@example.com");
        tenant.setFid(UUID.randomUUID().toString());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.updateQuota(10, 50);

        // Persist first to get DB-generated ID, then update it via a second save
        // Since tenantId is @UuidGenerator, we use a workaround: set it before save
        try {
            var field = Tenant.class.getDeclaredField("tenantId");
            field.setAccessible(true);
            field.set(tenant, tenantId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set tenantId", e);
        }

        return tenantRepository.save(tenant);
    }
}
