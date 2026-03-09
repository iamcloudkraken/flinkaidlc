package com.flinkaidlc.platform.security;

import java.util.UUID;

public record TenantAuthenticationPrincipal(UUID tenantId) {}
