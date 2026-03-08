package com.flinkaidlc.platform.repository;

import com.flinkaidlc.platform.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
    Optional<Tenant> findByFid(String fid);
}
