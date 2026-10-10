package com.example.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerTransaction(
        String id,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal sourceAmount,
        BigDecimal destinationAmount,
        String sourceCurrency,
        String destinationCurrency,
        BigDecimal exchangeRate,
        Instant timestamp,
        TransactionType type,
        String idempotencyKey,
        String reversalOf) {
}
