package com.example.ledger.service;

import com.example.ledger.api.AccountRequests;
import com.example.ledger.api.LedgerResponses;
import com.example.ledger.api.TransactionRequests;
import com.example.ledger.domain.Account;
import com.example.ledger.domain.LedgerTransaction;
import com.example.ledger.domain.MonthClose;
import com.example.ledger.domain.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LedgerService {
    private static final int SCALE = 2;
    private final Object ledgerLock = new Object();
    private final ExchangeRateProvider exchangeRateProvider;
    private final Clock clock;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, LedgerTransaction> transactions = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyToTransaction = new ConcurrentHashMap<>();
    private final Map<YearMonth, MonthClose> monthCloses = new ConcurrentHashMap<>();

    public LedgerService(ExchangeRateProvider exchangeRateProvider, Clock clock) {
        this.exchangeRateProvider = exchangeRateProvider;
        this.clock = clock;
    }

    public Account createAccount(AccountRequests.CreateAccount request) {
        synchronized (ledgerLock) {
            String id = request.id().trim();
            if (accounts.containsKey(id)) {
                throw new LedgerException(HttpStatus.CONFLICT, "Account already exists: " + id);
            }
            BigDecimal opening = money(request.openingBalance());
            if (opening.signum() < 0) {
                throw new LedgerException(HttpStatus.BAD_REQUEST, "Opening balance cannot be negative");
            }
            String currency = request.currency().trim().toUpperCase();
            exchangeRateProvider.rate(currency, currency);
            Account account = new Account(id, currency, opening, clock.instant());
            accounts.put(id, account);
            return account;
        }
    }

    public LedgerTransaction createTransfer(TransactionRequests.CreateTransaction request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
        synchronized (ledgerLock) {
            String existingId = idempotencyToTransaction.get(idempotencyKey);
            LedgerTransaction existing = existingId == null ? null : transactions.get(existingId);
            if (existing != null) {
                return existing;
            }
            Account source = account(request.sourceAccountId());
            Account destination = account(request.destinationAccountId());
            BigDecimal amount = money(request.amount());
            if (amount.signum() <= 0) {
                throw new LedgerException(HttpStatus.BAD_REQUEST, "Transfer amount must be greater than zero");
            }
            BigDecimal rate = exchangeRateProvider.rate(source.currency(), destination.currency());
            BigDecimal destinationAmount = amount.multiply(rate).setScale(SCALE, RoundingMode.HALF_EVEN);
            if (destinationAmount.signum() <= 0) {
                throw new LedgerException(HttpStatus.BAD_REQUEST,
                        "Transfer amount is too small to convert to " + destination.currency());
            }
            return postTransfer(source, destination, amount, destinationAmount, rate, TransactionType.TRANSFER,
                    idempotencyKey, null);
        }
    }

    public LedgerTransaction reverse(String transactionId, String idempotencyKey) {
        synchronized (ledgerLock) {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                String existingId = idempotencyToTransaction.get(idempotencyKey);
                LedgerTransaction existing = existingId == null ? null : transactions.get(existingId);
                if (existing != null) {
                    return existing;
                }
            }
            LedgerTransaction original = transaction(transactionId);
            if (original.type() == TransactionType.REVERSAL) {
                throw new LedgerException(HttpStatus.CONFLICT, "A reversal cannot be reversed");
            }
            boolean alreadyReversed = transactions.values().stream()
                    .anyMatch(item -> transactionId.equals(item.reversalOf()));
            if (alreadyReversed) {
                throw new LedgerException(HttpStatus.CONFLICT, "Transaction is already reversed");
            }
            Account source = account(original.destinationAccountId());
            Account destination = account(original.sourceAccountId());
            // Swap the original amounts exactly. Converting again would round a second time and could
            // return more or less than was originally debited.
            BigDecimal inverseRate = BigDecimal.ONE.divide(original.exchangeRate(), 10, RoundingMode.HALF_EVEN);
            return postTransfer(source, destination, original.destinationAmount(), original.sourceAmount(),
                    inverseRate, TransactionType.REVERSAL, idempotencyKey, original.id());
        }
    }

    public Account account(String id) {
        Account account = accounts.get(id);
        if (account == null) {
            throw new LedgerException(HttpStatus.NOT_FOUND, "Account not found: " + id);
        }
        return account;
    }

    public List<LedgerTransaction> history(String accountId) {
        account(accountId);
        return transactions.values().stream()
                .filter(item -> item.sourceAccountId().equals(accountId) || item.destinationAccountId().equals(accountId))
                .sorted(Comparator.comparing(LedgerTransaction::timestamp))
                .toList();
    }

    public LedgerResponses.ReconciliationResponse reconcile() {
        synchronized (ledgerLock) {
            List<String> discrepancies = new ArrayList<>();
            Map<String, BigDecimal[]> totals = new TreeMap<>();
            for (Account account : accounts.values()) {
                BigDecimal expected = expectedBalance(account, null);
                addToTotals(totals, account.currency(), account.balance(), expected);
                if (account.balance().compareTo(expected) != 0) {
                    discrepancies.add(account.id() + ": expected " + expected + ", actual " + account.balance());
                }
            }
            return reconciliation(totals, discrepancies);
        }
    }

    /**
     * Records each account's balance at the end of {@code month}. Closing is idempotent: closing an already
     * closed month returns the original record, so later changes to history cannot rewrite it.
     */
    public MonthClose closeMonth(YearMonth month) {
        synchronized (ledgerLock) {
            MonthClose existing = monthCloses.get(month);
            if (existing != null) {
                return existing;
            }
            Instant cutoff = cutoff(month);
            Instant now = clock.instant();
            if (now.isBefore(cutoff)) {
                throw new LedgerException(HttpStatus.CONFLICT, "Month " + month + " has not ended yet");
            }
            Map<String, BigDecimal> closingBalances = new TreeMap<>();
            for (Account account : accounts.values()) {
                if (account.createdAt().isBefore(cutoff)) {
                    // Postings made between the cutoff and now belong to the next month.
                    closingBalances.put(account.id(), account.balance().subtract(netPostingsSince(account, cutoff)));
                }
            }
            MonthClose close = new MonthClose(month, cutoff, now, Collections.unmodifiableMap(closingBalances),
                    transactionCountBefore(cutoff));
            monthCloses.put(month, close);
            return close;
        }
    }

    /** Checks a closed month's recorded closing balances against a replay of the transaction history. */
    public LedgerResponses.ReconciliationResponse reconcile(YearMonth month) {
        synchronized (ledgerLock) {
            MonthClose close = monthCloses.get(month);
            if (close == null) {
                throw new LedgerException(HttpStatus.NOT_FOUND, "Month " + month + " has not been closed");
            }
            List<String> discrepancies = new ArrayList<>();
            Map<String, BigDecimal[]> totals = new TreeMap<>();
            for (Map.Entry<String, BigDecimal> entry : close.closingBalances().entrySet()) {
                Account account = account(entry.getKey());
                BigDecimal closing = entry.getValue();
                BigDecimal expected = expectedBalance(account, close.cutoff());
                addToTotals(totals, account.currency(), closing, expected);
                if (closing.compareTo(expected) != 0) {
                    discrepancies.add(account.id() + ": month-end closing " + closing + ", history gives " + expected);
                }
            }
            long transactionCount = transactionCountBefore(close.cutoff());
            if (transactionCount != close.transactionCount()) {
                discrepancies.add("Transactions dated before month-end: " + close.transactionCount()
                        + " at close, " + transactionCount + " now");
            }
            return reconciliation(totals, discrepancies);
        }
    }

    private LedgerTransaction postTransfer(Account source, Account destination,
                                           BigDecimal sourceAmount, BigDecimal destinationAmount, BigDecimal rate,
                                           TransactionType type, String idempotencyKey, String reversalOf) {
        if (source.id().equals(destination.id())) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "Source and destination must differ");
        }
        if (source.balance().compareTo(sourceAmount) < 0) {
            throw new LedgerException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient balance");
        }
        source.debit(sourceAmount);
        destination.credit(destinationAmount);
        LedgerTransaction transaction = new LedgerTransaction(UUID.randomUUID().toString(), source.id(), destination.id(),
                sourceAmount, destinationAmount, source.currency(), destination.currency(), rate, clock.instant(),
                type, idempotencyKey, reversalOf);
        transactions.put(transaction.id(), transaction);
        if (idempotencyKey != null) {
            idempotencyToTransaction.put(idempotencyKey, transaction.id());
        }
        return transaction;
    }

    private BigDecimal expectedBalance(Account account, Instant before) {
        BigDecimal expected = account.openingBalance();
        for (LedgerTransaction item : transactions.values()) {
            if (before != null && !item.timestamp().isBefore(before)) {
                continue;
            }
            if (item.sourceAccountId().equals(account.id())) {
                expected = expected.subtract(item.sourceAmount());
            }
            if (item.destinationAccountId().equals(account.id())) {
                expected = expected.add(item.destinationAmount());
            }
        }
        return expected.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private BigDecimal netPostingsSince(Account account, Instant since) {
        BigDecimal net = BigDecimal.ZERO;
        for (LedgerTransaction item : transactions.values()) {
            if (item.timestamp().isBefore(since)) {
                continue;
            }
            if (item.sourceAccountId().equals(account.id())) {
                net = net.subtract(item.sourceAmount());
            }
            if (item.destinationAccountId().equals(account.id())) {
                net = net.add(item.destinationAmount());
            }
        }
        return net;
    }

    private long transactionCountBefore(Instant cutoff) {
        return transactions.values().stream().filter(item -> item.timestamp().isBefore(cutoff)).count();
    }

    private static Instant cutoff(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static void addToTotals(Map<String, BigDecimal[]> totals, String currency,
                                    BigDecimal actual, BigDecimal expected) {
        BigDecimal[] total = totals.computeIfAbsent(currency,
                key -> new BigDecimal[] {BigDecimal.ZERO.setScale(SCALE), BigDecimal.ZERO.setScale(SCALE)});
        total[0] = total[0].add(actual);
        total[1] = total[1].add(expected);
    }

    private static LedgerResponses.ReconciliationResponse reconciliation(Map<String, BigDecimal[]> totals,
                                                                        List<String> discrepancies) {
        Map<String, LedgerResponses.CurrencyTotal> byCurrency = new TreeMap<>();
        totals.forEach((currency, total) ->
                byCurrency.put(currency, new LedgerResponses.CurrencyTotal(total[0], total[1])));
        return new LedgerResponses.ReconciliationResponse(discrepancies.isEmpty(), byCurrency, discrepancies);
    }

    private LedgerTransaction transaction(String id) {
        LedgerTransaction transaction = transactions.get(id);
        if (transaction == null) {
            throw new LedgerException(HttpStatus.NOT_FOUND, "Transaction not found: " + id);
        }
        return transaction;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.scale() > SCALE) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "Money must have at most two decimal places");
        }
        return value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
