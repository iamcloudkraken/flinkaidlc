package com.flinkaidlc.platform.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfig {

    @Bean
    public KubernetesClient kubernetesClient() {
        // Auto-detects: in-cluster service account when KUBERNETES_SERVICE_HOST is set,
        // falls back to ~/.kube/config for local dev
        return new KubernetesClientBuilder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "k8s.provisioner.enabled", havingValue = "true", matchIfMissing = true)
    public MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>
    flinkDeploymentClient(KubernetesClient client) {
        return client.genericKubernetesResources("flink.apache.org/v1beta1", "FlinkDeployment");
    }
}
