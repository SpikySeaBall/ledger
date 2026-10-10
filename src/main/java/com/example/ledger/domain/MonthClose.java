package com.example.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;

/** Closing balances recorded when a month is closed; later month-end reconciliations are checked against it. */
public record MonthClose(YearMonth month, Instant cutoff, Instant closedAt,
                         Map<String, BigDecimal> closingBalances, long transactionCount) {
}
