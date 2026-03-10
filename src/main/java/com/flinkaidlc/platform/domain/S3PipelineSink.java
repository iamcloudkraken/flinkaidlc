package com.flinkaidlc.platform.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@DiscriminatorValue("S3")
@Getter
@Setter
@NoArgsConstructor
public class S3PipelineSink extends PipelineSink {

    @Column(name = "s3_bucket")
    private String bucket;

    @Column(name = "s3_prefix")
    private String prefix;

    @Column(name = "s3_partitioned")
    private boolean partitioned = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "s3_auth_type", length = 20)
    private S3AuthType authType;

    @Column(name = "s3_access_key")
    private String accessKey;

    @Column(name = "s3_secret_key")
    private String secretKey;

    @Column(name = "columns", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<ColumnDefinition> columns = new ArrayList<>();

    @Column(name = "s3_partition_columns", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> s3PartitionColumns = new ArrayList<>();
}
