package com.realbank.transactionmigration.config;

import com.realbank.transactionmigration.batch.IdRangePartitioner;
import com.realbank.transactionmigration.batch.TransactionManagerItemWriter;
import com.realbank.transactionmigration.model.JoinedTransactionRecord;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;

@Configuration
public class BatchConfig {

    private static final int GRID_SIZE = 8;
    private static final int CHUNK_SIZE = 500;

    @Bean
    public Job transactionMigrationJob(JobRepository jobRepository, Step migrationMasterStep) {
        return new JobBuilder("transactionMigrationJob", jobRepository)
                .start(migrationMasterStep)
                .build();
    }

    @Bean
    @JobScope
    public IdRangePartitioner idRangePartitioner(
            JdbcTemplate jdbcTemplate,
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate
    ) {
        return new IdRangePartitioner(jdbcTemplate,
                LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    @Bean
    public Step migrationMasterStep(
            JobRepository jobRepository,
            IdRangePartitioner idRangePartitioner,
            Step migrationSlaveStep,
            TaskExecutor migrationTaskExecutor
    ) {
        TaskExecutorPartitionHandler partitionHandler = new TaskExecutorPartitionHandler();
        partitionHandler.setStep(migrationSlaveStep);
        partitionHandler.setGridSize(GRID_SIZE);
        partitionHandler.setTaskExecutor(migrationTaskExecutor);

        return new StepBuilder("migrationMasterStep", jobRepository)
                .partitioner("migrationSlaveStep", idRangePartitioner)
                .partitionHandler(partitionHandler)
                .build();
    }

    @Bean
    public Step migrationSlaveStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcCursorItemReader<JoinedTransactionRecord> transactionJoinReader,
            TransactionManagerItemWriter transactionManagerItemWriter
    ) {
        return new StepBuilder("migrationSlaveStep", jobRepository)
                .<JoinedTransactionRecord, JoinedTransactionRecord>chunk(CHUNK_SIZE, transactionManager)
                .reader(transactionJoinReader)
                .writer(transactionManagerItemWriter)
                .build();
    }

    @Bean
    @StepScope
    public JdbcCursorItemReader<JoinedTransactionRecord> transactionJoinReader(
            DataSource dataSource,
            @Value("#{stepExecutionContext['bookingDate']}") String bookingDate,
            @Value("#{stepExecutionContext['minId']}") Long minId,
            @Value("#{stepExecutionContext['maxId']}") Long maxId
    ) {
        JdbcCursorItemReader<JoinedTransactionRecord> reader = new JdbcCursorItemReader<>();
        reader.setName("transactionJoinReader");
        reader.setDataSource(dataSource);

        if (bookingDate == null || bookingDate.isBlank() || maxId < minId) {
            reader.setSql("SELECT 1 FROM transaction_loan_history WHERE 1 = 0");
            reader.setRowMapper((rs, rowNum) -> null);
            return reader;
        }

        // id BETWEEN → direct PK range scan (no full-partition scan)
        // booking_date filter → MySQL partition pruning on both tables
        reader.setSql("""
                SELECT
                    th.account_id,
                    th.currency,
                    tlh.record_id,
                    tlh.booking_date,
                    tlh.amount,
                    tlh.beneficiary
                FROM transaction_loan_history tlh
                INNER JOIN transaction_history th
                    ON th.record_id = tlh.record_id
                   AND th.booking_date = tlh.booking_date
                WHERE tlh.id BETWEEN %d AND %d
                  AND tlh.booking_date >= '%s'
                  AND tlh.booking_date <  DATE_ADD('%s', INTERVAL 1 DAY)
                ORDER BY tlh.id
                """.formatted(minId, maxId, bookingDate, bookingDate));
        reader.setRowMapper((rs, rowNum) -> {
            JoinedTransactionRecord record = new JoinedTransactionRecord();
            record.setAccountId(rs.getString("account_id"));
            record.setCurrency(rs.getString("currency"));
            record.setRecordId(rs.getString("record_id"));
            record.setBookingDate(rs.getTimestamp("booking_date").toLocalDateTime());
            record.setAmount(rs.getBigDecimal("amount"));
            record.setBeneficiary(rs.getString("beneficiary"));
            return record;
        });
        return reader;
    }

    @Bean
    public TaskExecutor migrationTaskExecutor() {
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("migration-partition-");
        taskExecutor.setConcurrencyLimit(GRID_SIZE);
        return taskExecutor;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
