package com.realbank.transactionmigration.controller;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/migration")
public class MigrationController {

    private final JobLauncher jobLauncher;
    private final Job transactionMigrationJob;
    private final JdbcTemplate jdbcTemplate;

    public MigrationController(JobLauncher jobLauncher, Job transactionMigrationJob, JdbcTemplate jdbcTemplate) {
        this.jobLauncher = jobLauncher;
        this.transactionMigrationJob = transactionMigrationJob;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record MigrationRequest(String startDate, String endDate) {}

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runMigration(
            @RequestBody(required = false) MigrationRequest request
    ) throws Exception {
        LocalDate start;
        LocalDate end;

        if (request != null && request.startDate() != null && request.endDate() != null) {
            start = LocalDate.parse(request.startDate());
            end = LocalDate.parse(request.endDate());
        } else {
            // Default: toàn bộ date range có trong DB
            start = jdbcTemplate.queryForObject(
                    "SELECT DATE(MIN(booking_date)) FROM transaction_loan_history", LocalDate.class);
            end = jdbcTemplate.queryForObject(
                    "SELECT DATE(MAX(booking_date)) FROM transaction_loan_history", LocalDate.class);
            if (start == null || end == null) {
                return ResponseEntity.ok(Map.of(
                        "status", "SKIPPED",
                        "message", "No data found in transaction_loan_history"));
            }
        }

        JobParameters jobParameters = new JobParametersBuilder()
                .addString("startDate", start.toString())
                .addString("endDate", end.toString())
                .addString("runAt", LocalDateTime.now().toString())
                .toJobParameters();

        JobExecution execution = jobLauncher.run(transactionMigrationJob, jobParameters);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobExecutionId", execution.getId());
        result.put("status", execution.getStatus().name());
        result.put("startDate", start.toString());
        result.put("endDate", end.toString());
        result.put("readCount", execution.getStepExecutions().stream().mapToLong(se -> se.getReadCount()).sum());
        result.put("writeCount", execution.getStepExecutions().stream().mapToLong(se -> se.getWriteCount()).sum());
        return ResponseEntity.ok(result);
    }
}
