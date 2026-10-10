package com.example.ledger.api;

import com.example.ledger.domain.Account;
import com.example.ledger.domain.LedgerTransaction;
import com.example.ledger.domain.MonthClose;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public final class LedgerResponses {
    private LedgerResponses() {}

    public record AccountResponse(String id, String currency, BigDecimal openingBalance, BigDecimal balance) {
        public static AccountResponse from(Account account) {
            return new AccountResponse(account.id(), account.currency(), account.openingBalance(), account.balance());
        }
    }

    public record TransactionResponse(
            String id, String sourceAccountId, String destinationAccountId,
            BigDecimal sourceAmount, BigDecimal destinationAmount,
            String sourceCurrency, String destinationCurrency,
            BigDecimal exchangeRate, Instant timestamp, String type, String reversalOf) {
        public static TransactionResponse from(LedgerTransaction transaction) {
            return new TransactionResponse(transaction.id(), transaction.sourceAccountId(),
                    transaction.destinationAccountId(), transaction.sourceAmount(),
                    transaction.destinationAmount(), transaction.sourceCurrency(),
                    transaction.destinationCurrency(), transaction.exchangeRate(),
                    transaction.timestamp(), transaction.type().name(), transaction.reversalOf());
        }
    }

    /** Totals for one currency; amounts in different currencies are never added together. */
    public record CurrencyTotal(BigDecimal actual, BigDecimal expected) {}

    public record ReconciliationResponse(boolean balanced, Map<String, CurrencyTotal> totalsByCurrency,
                                         List<String> discrepancies) {}

    public record MonthCloseResponse(YearMonth month, Instant cutoff, Instant closedAt,
                                     Map<String, BigDecimal> closingBalances, long transactionCount) {
        public static MonthCloseResponse from(MonthClose close) {
            return new MonthCloseResponse(close.month(), close.cutoff(), close.closedAt(),
                    close.closingBalances(), close.transactionCount());
        }
    }
}
