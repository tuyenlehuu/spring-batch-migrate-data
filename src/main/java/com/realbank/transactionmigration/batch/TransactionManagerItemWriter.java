package com.realbank.transactionmigration.batch;

import com.realbank.transactionmigration.dto.FinTxtItemPayload;
import com.realbank.transactionmigration.dto.UpsertFinTxtRequest;
import com.realbank.transactionmigration.dto.UpsertFinTxtResponse;
import com.realbank.transactionmigration.model.JoinedTransactionRecord;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class TransactionManagerItemWriter implements ItemWriter<JoinedTransactionRecord> {

    private final RestTemplate restTemplate;
    private final String upsertUrl;

    public TransactionManagerItemWriter(
            RestTemplate restTemplate,
            @Value("${transaction-manager.upsert-url}") String upsertUrl
    ) {
        this.restTemplate = restTemplate;
        this.upsertUrl = upsertUrl;
    }

    @Override
    public void write(Chunk<? extends JoinedTransactionRecord> chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        List<FinTxtItemPayload> items = chunk.getItems().stream().map(record -> {
            FinTxtItemPayload payload = new FinTxtItemPayload();
            payload.setAccountId(record.getAccountId());
            payload.setCurrency(record.getCurrency());
            payload.setRecordId(record.getRecordId());
            payload.setBookingDate(record.getBookingDate());
            payload.setAmount(record.getAmount());
            payload.setBeneficiary(record.getBeneficiary());
            return payload;
        }).toList();

        ResponseEntity<UpsertFinTxtResponse> response = restTemplate.postForEntity(
                upsertUrl,
                new UpsertFinTxtRequest(items),
                UpsertFinTxtResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Upsert API returned empty or non-success response");
        }

        UpsertFinTxtResponse body = response.getBody();
        if (body.getFailureCount() > 0) {
            throw new IllegalStateException("Upsert API failed for " + body.getFailureCount() + " items");
        }
    }
}
