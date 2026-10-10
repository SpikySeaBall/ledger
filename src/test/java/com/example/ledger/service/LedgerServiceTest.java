package com.example.ledger.service;

import com.example.ledger.api.AccountRequests;
import com.example.ledger.api.TransactionRequests;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LedgerServiceTest {
    private LedgerService service() {
        return new LedgerService(new FixedExchangeRateProvider(), Clock.systemUTC());
    }

    /** A clock the test can move, to post transactions in a chosen month. */
    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(String now) { this.now = Instant.parse(now); }

        void set(String instant) { now = Instant.parse(instant); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void transferIsIdempotentAndUpdatesBothAccounts() {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", BigDecimal.ZERO));
        TransactionRequests.CreateTransaction request =
                new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("25.00"));

        var first = service.createTransfer(request, "same-request");
        var second = service.createTransfer(request, "same-request");

        assertEquals(first.id(), second.id());
        assertEquals(new BigDecimal("75.00"), service.account("a").balance());
        assertEquals(new BigDecimal("25.00"), service.account("b").balance());
    }

    @Test
    void concurrentTransfersDoNotOverspend() throws Exception {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", BigDecimal.ZERO));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new Future<?>[10];
            for (int i = 0; i < futures.length; i++) {
                int index = i;
                futures[i] = executor.submit(() -> {
                    try {
                        service.createTransfer(new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("15.00")),
                                "key-" + index);
                    } catch (LedgerException expected) {
                        assertEquals(422, expected.status().value());
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
        assertEquals(new BigDecimal("10.00"), service.account("a").balance());
        assertEquals(new BigDecimal("90.00"), service.account("b").balance());
    }

    @Test
    void concurrentTransfersIntoSameAccountLoseNoUpdates() throws Exception {
        LedgerService service = service();
        int senders = 20;
        int transfersPerSender = 50;
        service.createAccount(new AccountRequests.CreateAccount("sink", "USD", BigDecimal.ZERO));
        for (int s = 0; s < senders; s++) {
            service.createAccount(new AccountRequests.CreateAccount("src-" + s, "USD", new BigDecimal("1000.00")));
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int s = 0; s < senders; s++) {
            for (int n = 0; n < transfersPerSender; n++) {
                String source = "src-" + s;
                String key = source + "-" + n;
                tasks.add(() -> service.createTransfer(
                        new TransactionRequests.CreateTransaction(source, "sink", new BigDecimal("1.25")), key));
            }
        }

        runSimultaneously(tasks, 32);

        assertEquals(new BigDecimal("1250.00"), service.account("sink").balance());
        for (int s = 0; s < senders; s++) {
            assertEquals(new BigDecimal("937.50"), service.account("src-" + s).balance());
        }
        assertEquals(senders * transfersPerSender, service.history("sink").size());
        assertTrue(service.reconcile().balanced());
    }

    @Test
    void concurrentOppositeTransfersConserveMoneyWithoutDeadlock() throws Exception {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", new BigDecimal("100.00")));
        AtomicInteger succeeded = new AtomicInteger();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String source = i % 2 == 0 ? "a" : "b";
            String destination = i % 2 == 0 ? "b" : "a";
            String key = "swap-" + i;
            tasks.add(() -> {
                try {
                    service.createTransfer(new TransactionRequests.CreateTransaction(
                            source, destination, new BigDecimal("30.00")), key);
                    succeeded.incrementAndGet();
                } catch (LedgerException rejected) {
                    assertEquals(422, rejected.status().value());
                }
                return null;
            });
        }

        runSimultaneously(tasks, 32);

        BigDecimal a = service.account("a").balance();
        BigDecimal b = service.account("b").balance();
        assertEquals(new BigDecimal("200.00"), a.add(b));
        assertTrue(a.signum() >= 0 && b.signum() >= 0);
        assertEquals(succeeded.get(), service.history("a").size());
        assertTrue(service.reconcile().balanced());
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyApplyOnce() throws Exception {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", BigDecimal.ZERO));
        TransactionRequests.CreateTransaction request =
                new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("10.00"));
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> service.createTransfer(request, "one-key").id());
        }

        List<Object> ids = runSimultaneously(tasks, 32);

        Set<Object> distinct = new HashSet<>(ids);
        assertEquals(1, distinct.size());
        assertEquals(new BigDecimal("90.00"), service.account("a").balance());
        assertEquals(new BigDecimal("10.00"), service.account("b").balance());
    }

    /** Runs all tasks on a pool, releasing them together through a start gate, and returns their results. */
    private static List<Object> runSimultaneously(List<Callable<Object>> tasks, int threads) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reversalRestoresBalances() {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "EUR", BigDecimal.ZERO));
        var transaction = service.createTransfer(
                new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("10.00")), "transfer");

        service.reverse(transaction.id(), "reversal");

        assertEquals(new BigDecimal("100.00"), service.account("a").balance());
        assertEquals(new BigDecimal("0.00"), service.account("b").balance());
        assertTrue(service.reconcile().balanced());
    }

    @Test
    void crossCurrencyReversalReturnsExactlyTheOriginalAmounts() {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("usd", "USD", new BigDecimal("10.00")));
        service.createAccount(new AccountRequests.CreateAccount("gbp", "GBP", BigDecimal.ZERO));
        var transfer = service.createTransfer(
                new TransactionRequests.CreateTransaction("usd", "gbp", new BigDecimal("0.07")), "fx");
        assertEquals(new BigDecimal("0.06"), transfer.destinationAmount());

        var reversal = service.reverse(transfer.id(), "fx-reversal");

        assertEquals(new BigDecimal("0.06"), reversal.sourceAmount());
        assertEquals(new BigDecimal("0.07"), reversal.destinationAmount());
        assertEquals(new BigDecimal("10.00"), service.account("usd").balance());
        assertEquals(new BigDecimal("0.00"), service.account("gbp").balance());
        assertTrue(service.reconcile().balanced());
    }

    @Test
    void rejectsTransferThatConvertsToZero() {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("jpy", "JPY", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("usd", "USD", BigDecimal.ZERO));

        LedgerException error = assertThrows(LedgerException.class, () -> service.createTransfer(
                new TransactionRequests.CreateTransaction("jpy", "usd", new BigDecimal("0.01")), "tiny"));

        assertEquals(400, error.status().value());
        assertEquals(new BigDecimal("100.00"), service.account("jpy").balance());
    }

    @Test
    void reconciliationReportsTotalsPerCurrency() {
        LedgerService service = service();
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", new BigDecimal("50.00")));
        service.createAccount(new AccountRequests.CreateAccount("c", "EUR", BigDecimal.ZERO));
        service.createTransfer(new TransactionRequests.CreateTransaction("a", "c", new BigDecimal("10.00")), "fx");

        var report = service.reconcile();

        assertTrue(report.balanced());
        assertEquals(Set.of("USD", "EUR"), report.totalsByCurrency().keySet());
        assertEquals(new BigDecimal("140.00"), report.totalsByCurrency().get("USD").actual());
        assertEquals(new BigDecimal("140.00"), report.totalsByCurrency().get("USD").expected());
        assertEquals(new BigDecimal("9.20"), report.totalsByCurrency().get("EUR").actual());
    }

    @Test
    void monthEndReconciliationIsNotAffectedByLaterActivity() {
        TestClock clock = new TestClock("2026-09-15T10:00:00Z");
        LedgerService service = new LedgerService(new FixedExchangeRateProvider(), clock);
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", BigDecimal.ZERO));
        service.createTransfer(new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("30.00")), "sep");

        clock.set("2026-10-02T09:00:00Z");
        service.createTransfer(new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("5.00")), "oct-1");
        service.createAccount(new AccountRequests.CreateAccount("late", "USD", new BigDecimal("1.00")));
        var close = service.closeMonth(YearMonth.of(2026, 9));

        assertEquals(new BigDecimal("70.00"), close.closingBalances().get("a"));
        assertEquals(new BigDecimal("30.00"), close.closingBalances().get("b"));
        assertFalse(close.closingBalances().containsKey("late"));
        assertEquals(1, close.transactionCount());

        clock.set("2026-10-20T09:00:00Z");
        service.createTransfer(new TransactionRequests.CreateTransaction("b", "a", new BigDecimal("12.00")), "oct-2");
        var report = service.reconcile(YearMonth.of(2026, 9));

        assertTrue(report.balanced(), report.discrepancies().toString());
        assertEquals(new BigDecimal("100.00"), report.totalsByCurrency().get("USD").actual());
        assertSame(close, service.closeMonth(YearMonth.of(2026, 9)));
    }

    @Test
    void monthEndReconciliationDetectsBackdatedTransaction() {
        TestClock clock = new TestClock("2026-09-15T10:00:00Z");
        LedgerService service = new LedgerService(new FixedExchangeRateProvider(), clock);
        service.createAccount(new AccountRequests.CreateAccount("a", "USD", new BigDecimal("100.00")));
        service.createAccount(new AccountRequests.CreateAccount("b", "USD", BigDecimal.ZERO));
        clock.set("2026-10-01T00:00:00Z");
        service.closeMonth(YearMonth.of(2026, 9));

        clock.set("2026-09-30T23:00:00Z");
        service.createTransfer(new TransactionRequests.CreateTransaction("a", "b", new BigDecimal("8.00")), "late");
        var report = service.reconcile(YearMonth.of(2026, 9));

        assertFalse(report.balanced());
        assertEquals(3, report.discrepancies().size(), report.discrepancies().toString());
    }

    @Test
    void monthMustBeFinishedBeforeCloseAndClosedBeforeReconciliation() {
        TestClock clock = new TestClock("2026-10-10T12:00:00Z");
        LedgerService service = new LedgerService(new FixedExchangeRateProvider(), clock);

        assertEquals(409, assertThrows(LedgerException.class,
                () -> service.closeMonth(YearMonth.of(2026, 10))).status().value());
        assertEquals(404, assertThrows(LedgerException.class,
                () -> service.reconcile(YearMonth.of(2026, 9))).status().value());
    }

    @Test
    void rejectsMoreThanTwoDecimalPlacesAndUnsupportedCurrency() {
        LedgerService service = service();
        assertThrows(LedgerException.class, () -> service.createAccount(
                new AccountRequests.CreateAccount("fractional", "USD", new BigDecimal("1.001"))));
        assertThrows(LedgerException.class, () -> service.createAccount(
                new AccountRequests.CreateAccount("unknown", "AUD", BigDecimal.ZERO)));
    }
}
