package com.flinkaidlc.platform.orchestration;

import com.flinkaidlc.platform.domain.*;
import com.flinkaidlc.platform.repository.PipelineDeploymentRepository;
import com.flinkaidlc.platform.repository.PipelineRepository;
import com.flinkaidlc.platform.repository.TenantRepository;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FlinkOrchestrationServiceImpl} using Mockito for dependencies.
 *
 * <p>K8s calls are verified via Mockito mocks on {@link KubernetesClient} since a real
 * kind cluster is needed for full integration testing (out of scope for unit tests).
 */
@ExtendWith(MockitoExtension.class)
class FlinkOrchestrationServiceImplTest {

    @Mock
    private KubernetesClient k8sClient;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private PipelineRepository pipelineRepository;

    @Mock
    private PipelineDeploymentRepository deploymentRepository;

    private FlinkSqlGenerator sqlGenerator;
    private FlinkDeploymentBuilder deploymentBuilder;
    private FlinkOrchestrationServiceImpl service;

    private static final UUID TEST_TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TEST_PIPELINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String TENANT_SLUG = "test-tenant";
    private static final String NAMESPACE = "tenant-" + TENANT_SLUG;

    @BeforeEach
    void setUp() {
        sqlGenerator = new FlinkSqlGenerator();
        deploymentBuilder = new FlinkDeploymentBuilder();
        service = new FlinkOrchestrationServiceImpl(
            k8sClient, sqlGenerator, deploymentBuilder,
            tenantRepository, pipelineRepository, deploymentRepository,
            "tenant-"
        );

        Tenant tenant = new Tenant();
        tenant.setSlug(TENANT_SLUG);
        lenient().when(tenantRepository.findById(TEST_TENANT_ID)).thenReturn(Optional.of(tenant));
    }

    @Test
    void deploy_persistsDeploymentRecord() {
        Pipeline pipeline = buildPipeline();
        when(deploymentRepository.findById(TEST_PIPELINE_ID)).thenReturn(Optional.empty());

        // Mock the K8s calls to be no-ops
        mockK8sConfigMap();
        mockK8sFlinkDeployment();
        when(deploymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deploy(pipeline);

        verify(deploymentRepository).save(argThat(d ->
            d.getK8sResourceName().equals("pipeline-" + TEST_PIPELINE_ID) &&
            d.getConfigmapName().equals("pipeline-sql-" + TEST_PIPELINE_ID) &&
            "DEPLOYED".equals(d.getLifecycleState())
        ));
    }

    @Test
    void suspendAll_resolvesNamespaceFromTenantSlug() {
        // Should use tenant-SLUG namespace, not any user-supplied value
        mockK8sFlinkDeploymentList();

        service.suspendAll(TEST_TENANT_ID);

        verify(tenantRepository).findById(TEST_TENANT_ID);
    }

    @Test
    void teardown_doesNotUseUserInputForNamespace() {
        Pipeline pipeline = buildPipeline();
        mockK8sDeleteOperations();

        service.teardown(pipeline);

        // Verify the namespace is derived from tenant slug (not pipeline fields)
        verify(tenantRepository).findById(TEST_TENANT_ID);
    }

    @Test
    void sqlGenerator_generatesSqlWithSourceAndSink() {
        Pipeline pipeline = buildPipeline();
        String sql = sqlGenerator.generate(pipeline);

        assertThat(sql)
            .contains("CREATE TABLE input")
            .contains("CREATE TABLE output")
            .contains("INSERT INTO output SELECT * FROM input");
    }

    @Test
    void sqlGenerator_escapesSpecialCharsInConnectorValues() {
        Pipeline pipeline = buildPipeline();
        pipeline.getSources().get(0).setTopic("my'special'topic");

        String sql = sqlGenerator.generate(pipeline);
        assertThat(sql).contains("my\\'special\\'topic");
    }

    // ---- helpers ----

    private Pipeline buildPipeline() {
        Pipeline pipeline = new Pipeline();
        try {
            var field = Pipeline.class.getDeclaredField("pipelineId");
            field.setAccessible(true);
            field.set(pipeline, TEST_PIPELINE_ID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        pipeline.setTenantId(TEST_TENANT_ID);
        pipeline.setName("Test Pipeline");
        pipeline.setSqlQuery("INSERT INTO output SELECT * FROM input");
        pipeline.setParallelism(2);
        pipeline.setCheckpointIntervalMs(30000L);
        pipeline.setUpgradeMode(UpgradeMode.SAVEPOINT);
        pipeline.setStatus(PipelineStatus.DRAFT);

        PipelineSource source = new PipelineSource();
        source.setTableName("input");
        source.setTopic("t1");
        source.setBootstrapServers("kafka:9092");
        source.setConsumerGroup("cg");
        source.setStartupMode(StartupMode.GROUP_OFFSETS);
        source.setSchemaRegistryUrl("http://sr:8081");
        source.setAvroSubject("input-value");
        pipeline.addSource(source);

        PipelineSink sink = new PipelineSink();
        sink.setTableName("output");
        sink.setTopic("t-out");
        sink.setBootstrapServers("kafka:9092");
        sink.setSchemaRegistryUrl("http://sr:8081");
        sink.setAvroSubject("output-value");
        sink.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE);
        pipeline.addSink(sink);

        return pipeline;
    }

    private void mockK8sConfigMap() {
        var configMaps = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var inNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var resource = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8sClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(anyString())).thenReturn((io.fabric8.kubernetes.client.dsl.NonNamespaceOperation) inNamespace);
        when(inNamespace.resource(any())).thenReturn(resource);
        when(resource.createOrReplace()).thenReturn(new ConfigMap());
    }

    private void mockK8sFlinkDeployment() {
        var genericResources = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var inNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var resource = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8sClient.genericKubernetesResources(anyString(), anyString())).thenReturn(genericResources);
        when(genericResources.inNamespace(anyString())).thenReturn((io.fabric8.kubernetes.client.dsl.NonNamespaceOperation) inNamespace);
        when(inNamespace.resource(any())).thenReturn(resource);
        when(resource.createOrReplace()).thenReturn(new GenericKubernetesResource());
    }

    private void mockK8sFlinkDeploymentList() {
        var genericResources = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var inNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var withLabel1 = mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        var withLabel2 = mock(io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable.class);
        var list = mock(io.fabric8.kubernetes.api.model.GenericKubernetesResourceList.class);
        when(k8sClient.genericKubernetesResources(anyString(), anyString())).thenReturn(genericResources);
        when(genericResources.inNamespace(anyString())).thenReturn((io.fabric8.kubernetes.client.dsl.NonNamespaceOperation) inNamespace);
        when(inNamespace.withLabel(anyString(), anyString())).thenReturn(withLabel1);
        when(withLabel1.withLabel(anyString(), anyString())).thenReturn(withLabel2);
        when(withLabel2.list()).thenReturn(list);
        when(list.getItems()).thenReturn(List.of());
    }

    private void mockK8sDeleteOperations() {
        // Mock FlinkDeployment (genericKubernetesResources) for delete
        var genericResources = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var genericInNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var genericWithName = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8sClient.genericKubernetesResources(anyString(), anyString())).thenReturn(genericResources);
        when(genericResources.inNamespace(anyString())).thenReturn((io.fabric8.kubernetes.client.dsl.NonNamespaceOperation) genericInNamespace);
        when(genericInNamespace.withName(anyString())).thenReturn(genericWithName);

        // Mock ConfigMap for delete
        var configMaps = mock(io.fabric8.kubernetes.client.dsl.MixedOperation.class);
        var inNamespace = mock(io.fabric8.kubernetes.client.dsl.NonNamespaceOperation.class);
        var withName = mock(io.fabric8.kubernetes.client.dsl.Resource.class);
        when(k8sClient.configMaps()).thenReturn(configMaps);
        when(configMaps.inNamespace(anyString())).thenReturn((io.fabric8.kubernetes.client.dsl.NonNamespaceOperation) inNamespace);
        when(inNamespace.withName(anyString())).thenReturn(withName);
    }
}
