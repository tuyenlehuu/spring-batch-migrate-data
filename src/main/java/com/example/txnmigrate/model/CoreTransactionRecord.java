package com.example.txnmigrate.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CoreTransactionRecord(
        Long id,
        String recordId,
        String accountId,
        BigDecimal amount,
        String currency,
        LocalDate bookingDate
) {
}
