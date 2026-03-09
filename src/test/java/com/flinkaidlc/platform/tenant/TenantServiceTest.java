package com.flinkaidlc.platform.tenant;

import com.flinkaidlc.platform.domain.Tenant;
import com.flinkaidlc.platform.domain.TenantStatus;
import com.flinkaidlc.platform.exception.SlugAlreadyInUseException;
import com.flinkaidlc.platform.exception.TenantNotFoundException;
import com.flinkaidlc.platform.exception.TenantProvisioningException;
import com.flinkaidlc.platform.k8s.ITenantNamespaceProvisioner;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderClient;
import com.flinkaidlc.platform.oauth2.OAuth2ProviderException;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import com.flinkaidlc.platform.domain.PipelineStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private ITenantNamespaceProvisioner provisioner;

    @Mock
    private OAuth2ProviderClient oauth2Client;

    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new TenantService(tenantRepository, pipelineRepository, provisioner, oauth2Client);
    }

    // ---- onboardTenant ----

    @Test
    void onboardTenant_success_returnsFidAndSecret() {
        OnboardTenantRequest request = new OnboardTenantRequest("Acme Corp", "acme", "admin@acme.com", 10, 50);

        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());

        Tenant saved = tenantWithId(UUID.randomUUID(), "acme");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(saved);

        OnboardTenantResponse response = tenantService.onboardTenant(request);

        assertThat(response.slug()).isEqualTo("acme");
        assertThat(response.fid()).isNotBlank();
        assertThat(response.fidSecret()).isNotBlank();
        assertThat(response.namespaceProvisioned()).isTrue();

        verify(provisioner).provision(eq("acme"), eq(10), eq(50));
        verify(oauth2Client).registerClient(anyString(), anyString());
    }

    @Test
    void onboardTenant_duplicateSlug_throwsSlugAlreadyInUseException() {
        OnboardTenantRequest request = new OnboardTenantRequest("Acme Corp", "acme", "admin@acme.com", 10, 50);
        Tenant existing = tenantWithId(UUID.randomUUID(), "acme");
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> tenantService.onboardTenant(request))
            .isInstanceOf(SlugAlreadyInUseException.class)
            .hasMessageContaining("acme");

        verify(tenantRepository, never()).save(any());
        verifyNoInteractions(provisioner, oauth2Client);
    }

    @Test
    void onboardTenant_k8sProvisioningFails_rollsBackDbRecord() {
        OnboardTenantRequest request = new OnboardTenantRequest("Acme Corp", "acme", "admin@acme.com", 10, 50);
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());

        Tenant saved = tenantWithId(UUID.randomUUID(), "acme");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(saved);

        doThrow(new RuntimeException("K8s unavailable")).when(provisioner)
            .provision(anyString(), anyInt(), anyInt());

        assertThatThrownBy(() -> tenantService.onboardTenant(request))
            .isInstanceOf(TenantProvisioningException.class)
            .hasMessageContaining("Kubernetes provisioning failed");

        verify(tenantRepository).delete(saved);
        verifyNoInteractions(oauth2Client);
    }

    @Test
    void onboardTenant_oauth2Fails_rollsBackDbAndDeprovisionsK8s() {
        OnboardTenantRequest request = new OnboardTenantRequest("Acme Corp", "acme", "admin@acme.com", 10, 50);
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());

        Tenant saved = tenantWithId(UUID.randomUUID(), "acme");
        when(tenantRepository.save(any(Tenant.class))).thenReturn(saved);

        doThrow(new OAuth2ProviderException("Keycloak down")).when(oauth2Client)
            .registerClient(anyString(), anyString());

        assertThatThrownBy(() -> tenantService.onboardTenant(request))
            .isInstanceOf(TenantProvisioningException.class)
            .hasMessageContaining("OAuth2 provider unavailable");

        verify(provisioner).deprovision("acme");
        verify(tenantRepository).delete(saved);
    }

    @Test
    void onboardTenant_persistsTenantWithCorrectFields() {
        OnboardTenantRequest request = new OnboardTenantRequest("Acme Corp", "acme", "admin@acme.com", 5, 20);
        when(tenantRepository.findBySlug("acme")).thenReturn(Optional.empty());

        Tenant saved = tenantWithId(UUID.randomUUID(), "acme");
        ArgumentCaptor<Tenant> captor = ArgumentCaptor.forClass(Tenant.class);
        when(tenantRepository.save(captor.capture())).thenReturn(saved);

        tenantService.onboardTenant(request);

        Tenant persisted = captor.getValue();
        assertThat(persisted.getSlug()).isEqualTo("acme");
        assertThat(persisted.getName()).isEqualTo("Acme Corp");
        assertThat(persisted.getContactEmail()).isEqualTo("admin@acme.com");
        assertThat(persisted.getMaxPipelines()).isEqualTo(5);
        assertThat(persisted.getMaxTotalParallelism()).isEqualTo(20);
        assertThat(persisted.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(persisted.getFid()).isNotBlank();
    }

    // ---- getTenantWithUsage ----

    @Test
    void getTenantWithUsage_returnsCorrectUsageCounts() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithId(tenantId, "acme");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(pipelineRepository.countByTenantIdAndStatusNot(tenantId, PipelineStatus.DELETED)).thenReturn(3L);
        when(pipelineRepository.sumParallelismByTenantIdExcludingStatuses(eq(tenantId), anyList())).thenReturn(12L);

        TenantResponse response = tenantService.getTenantWithUsage(tenantId);

        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.usedPipelines()).isEqualTo(3L);
        assertThat(response.usedParallelism()).isEqualTo(12L);
    }

    @Test
    void getTenantWithUsage_tenantNotFound_throwsTenantNotFoundException() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.getTenantWithUsage(tenantId))
            .isInstanceOf(TenantNotFoundException.class);
    }

    // ---- updateTenant ----

    @Test
    void updateTenant_quotaChange_patchesK8sResourceQuota() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithId(tenantId, "acme");
        tenant.updateQuota(10, 50);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenReturn(tenant);
        when(pipelineRepository.countByTenantIdAndStatusNot(any(), any())).thenReturn(0L);
        when(pipelineRepository.sumParallelismByTenantIdExcludingStatuses(any(), anyList())).thenReturn(0L);

        UpdateTenantRequest request = new UpdateTenantRequest("New Name", "new@acme.com", 20, 100);
        tenantService.updateTenant(tenantId, request);

        verify(provisioner).patchResourceQuota("acme", 20, 100);
    }

    @Test
    void updateTenant_noQuotaChange_doesNotPatchK8s() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithId(tenantId, "acme");
        tenant.updateQuota(10, 50);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenReturn(tenant);
        when(pipelineRepository.countByTenantIdAndStatusNot(any(), any())).thenReturn(0L);
        when(pipelineRepository.sumParallelismByTenantIdExcludingStatuses(any(), anyList())).thenReturn(0L);

        UpdateTenantRequest request = new UpdateTenantRequest("Updated Name", "updated@acme.com", 10, 50);
        tenantService.updateTenant(tenantId, request);

        verify(provisioner, never()).patchResourceQuota(any(), anyInt(), anyInt());
    }

    // ---- deleteTenant ----

    @Test
    void deleteTenant_removesK8sNamespaceAndMarksTenantDeleted() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = tenantWithId(tenantId, "acme");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.save(any())).thenReturn(tenant);

        tenantService.deleteTenant(tenantId);

        verify(provisioner).deprovision("acme");
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.DELETED);
        verify(tenantRepository).save(tenant);
    }

    @Test
    void deleteTenant_tenantNotFound_throwsTenantNotFoundException() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tenantService.deleteTenant(tenantId))
            .isInstanceOf(TenantNotFoundException.class);

        verifyNoInteractions(provisioner);
    }

    // ---- helpers ----

    private Tenant tenantWithId(UUID id, String slug) {
        Tenant t = new Tenant();
        try {
            // Set tenantId via reflection since there's no public setter
            var field = Tenant.class.getDeclaredField("tenantId");
            field.setAccessible(true);
            field.set(t, id);
            var createdAt = Tenant.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(t, OffsetDateTime.now());
            var updatedAt = Tenant.class.getDeclaredField("updatedAt");
            updatedAt.setAccessible(true);
            updatedAt.set(t, OffsetDateTime.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        t.setSlug(slug);
        t.setName("Test Tenant");
        t.setContactEmail("test@example.com");
        t.setFid(UUID.randomUUID().toString());
        t.setStatus(TenantStatus.ACTIVE);
        t.updateQuota(10, 50);
        return t;
    }
}
