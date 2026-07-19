package com.example.txnmigrate.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "migration")
public record MigrationProperties(
        @Min(1) int chunkSize,
        @Min(1) int pageSize,
        @Min(1) int fetchSize,
        @Min(1) int gridSize,
        @Min(1) int threadPoolSize,
        @NotBlank String sourceTable,
        @NotBlank String serviceAUpsertUrl,
        Duration apiTimeout
) {
}
