package com.flinkaidlc.platform.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for self-service tenant registration.
 */
public record OnboardTenantRequest(

    @NotBlank(message = "name must not be blank")
    @Size(max = 255, message = "name must not exceed 255 characters")
    String name,

    @NotBlank(message = "slug must not be blank")
    @Pattern(
        regexp = "^[a-z0-9-]{3,63}$",
        message = "slug must match ^[a-z0-9-]{3,63}$"
    )
    String slug,

    @NotBlank(message = "contactEmail must not be blank")
    @Email(message = "contactEmail must be a valid email address")
    String contactEmail,

    @Min(value = 1, message = "maxPipelines must be >= 1")
    @Max(value = 1000, message = "maxPipelines must be <= 1000")
    int maxPipelines,

    @Min(value = 1, message = "maxTotalParallelism must be >= 1")
    @Max(value = 10000, message = "maxTotalParallelism must be <= 10000")
    int maxTotalParallelism
) {}
