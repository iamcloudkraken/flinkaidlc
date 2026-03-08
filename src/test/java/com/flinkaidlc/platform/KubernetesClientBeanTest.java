package com.flinkaidlc.platform;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesClientBeanTest extends AbstractIntegrationTest {

    @Autowired
    private KubernetesClient kubernetesClient;

    @Autowired
    private MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
            flinkDeploymentClient;

    @Test
    void kubernetesClientBeanInitializesWithoutError() {
        assertThat(kubernetesClient).isNotNull();
    }

    @Test
    void flinkDeploymentClientBeanInitializesWithoutError() {
        assertThat(flinkDeploymentClient).isNotNull();
    }
}
