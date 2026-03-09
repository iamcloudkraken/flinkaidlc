package com.flinkaidlc.platform.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for updating tenant metadata and resource limits.
 * All fields are updatable; quota fields also trigger a K8s ResourceQuota patch.
 */
public record UpdateTenantRequest(

    @NotBlank(message = "name must not be blank")
    String name,

    @NotBlank(message = "contactEmail must not be blank")
    @Email(message = "contactEmail must be a valid email address")
    String contactEmail,

    @Min(value = 1, message = "maxPipelines must be >= 1")
    int maxPipelines,

    @Min(value = 1, message = "maxTotalParallelism must be >= 1")
    int maxTotalParallelism
) {}
