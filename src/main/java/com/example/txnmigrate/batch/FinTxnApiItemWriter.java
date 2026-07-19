package com.example.txnmigrate.batch;

import com.example.txnmigrate.config.MigrationProperties;
import com.example.txnmigrate.model.CoreTransactionRecord;
import com.example.txnmigrate.model.FinTxnUpsertRequest;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

public class FinTxnApiItemWriter implements ItemWriter<CoreTransactionRecord> {

    private final WebClient webClient;
    private final String upsertUrl;
    private final Duration timeout;

    public FinTxnApiItemWriter(WebClient webClient, MigrationProperties properties) {
        this.webClient = webClient;
        this.upsertUrl = properties.serviceAUpsertUrl();
        this.timeout = properties.apiTimeout();
    }

    @Override
    public void write(Chunk<? extends CoreTransactionRecord> chunk) {
        List<FinTxnUpsertRequest> payload = chunk.getItems().stream()
                .map(item -> new FinTxnUpsertRequest(
                        item.recordId(),
                        item.accountId(),
                        item.amount(),
                        item.currency(),
                        item.bookingDate()
                ))
                .toList();

        webClient.post()
                .uri(upsertUrl)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block(timeout);
    }
}
