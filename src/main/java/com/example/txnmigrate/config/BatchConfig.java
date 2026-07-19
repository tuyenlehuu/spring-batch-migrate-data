package com.example.txnmigrate.config;

import com.example.txnmigrate.batch.FinTxnApiItemWriter;
import com.example.txnmigrate.batch.IdRangePartitioner;
import com.example.txnmigrate.model.CoreTransactionRecord;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.PagingQueryProvider;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class BatchConfig {

    private static final String JOB_NAME = "migrateTxnHistoryJob";
    private static final String MANAGER_STEP_NAME = "migrateTxnHistoryPartitionStep";
    private static final String WORKER_STEP_NAME = "migrateTxnHistoryWorkerStep";

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public Partitioner idRangePartitioner(DataSource sourceDataSource, MigrationProperties migrationProperties) {
        return new IdRangePartitioner(sourceDataSource, migrationProperties.sourceTable());
    }

    @Bean
    public TaskExecutor migrationTaskExecutor(MigrationProperties migrationProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("txn-migrate-");
        executor.setCorePoolSize(migrationProperties.threadPoolSize());
        executor.setMaxPoolSize(migrationProperties.threadPoolSize());
        executor.setQueueCapacity(0);
        executor.initialize();
        return executor;
    }

    @Bean
    @StepScope
    public PagingQueryProvider pagingQueryProvider(
            DataSource sourceDataSource,
            MigrationProperties migrationProperties,
            @Value("#{stepExecutionContext['minId']}") Long minId,
            @Value("#{stepExecutionContext['maxId']}") Long maxId
    ) throws Exception {
        SqlPagingQueryProviderFactoryBean provider = new SqlPagingQueryProviderFactoryBean();
        provider.setDataSource(sourceDataSource);
        provider.setSelectClause("SELECT id, record_id AS recordId, account_id AS accountId, amount, currency, booking_date AS bookingDate");
        provider.setFromClause("FROM " + migrationProperties.sourceTable());
        provider.setWhereClause("WHERE id BETWEEN :minId AND :maxId");
        provider.setSortKeys(Map.of("id", Order.ASCENDING));
        return provider.getObject();
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<CoreTransactionRecord> transactionHistoryReader(
            DataSource sourceDataSource,
            PagingQueryProvider pagingQueryProvider,
            MigrationProperties migrationProperties,
            @Value("#{stepExecutionContext['minId']}") Long minId,
            @Value("#{stepExecutionContext['maxId']}") Long maxId
    ) {
        return new JdbcPagingItemReaderBuilder<CoreTransactionRecord>()
                .name("transactionHistoryReader")
                .dataSource(sourceDataSource)
                .queryProvider(pagingQueryProvider)
                .parameterValues(Map.of("minId", minId, "maxId", maxId))
                .pageSize(migrationProperties.pageSize())
                .fetchSize(migrationProperties.fetchSize())
                .rowMapper(new DataClassRowMapper<>(CoreTransactionRecord.class))
                .saveState(true)
                .build();
    }

    @Bean
    public FinTxnApiItemWriter finTxnApiItemWriter(WebClient webClient, MigrationProperties migrationProperties) {
        return new FinTxnApiItemWriter(webClient, migrationProperties);
    }

    @Bean
    public Step migrateTxnHistoryWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<CoreTransactionRecord> transactionHistoryReader,
            FinTxnApiItemWriter finTxnApiItemWriter,
            MigrationProperties migrationProperties
    ) {
        return new StepBuilder(WORKER_STEP_NAME, jobRepository)
                .<CoreTransactionRecord, CoreTransactionRecord>chunk(migrationProperties.chunkSize(), transactionManager)
                .reader(transactionHistoryReader)
                .writer(finTxnApiItemWriter)
                .faultTolerant()
                .retryLimit(3)
                .retry(Exception.class)
                .build();
    }

    @Bean
    public Step migrateTxnHistoryPartitionStep(
            JobRepository jobRepository,
            MigrationProperties migrationProperties,
            TaskExecutor migrationTaskExecutor,
            Partitioner idRangePartitioner,
            Step migrateTxnHistoryWorkerStep
    ) {
        return new StepBuilder(MANAGER_STEP_NAME, jobRepository)
                .partitioner(WORKER_STEP_NAME, idRangePartitioner)
                .step(migrateTxnHistoryWorkerStep)
                .gridSize(migrationProperties.gridSize())
                .taskExecutor(migrationTaskExecutor)
                .build();
    }

    @Bean
    public Job migrateTxnHistoryJob(JobRepository jobRepository, Step migrateTxnHistoryPartitionStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(migrateTxnHistoryPartitionStep)
                .build();
    }
}
