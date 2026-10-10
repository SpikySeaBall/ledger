package com.example.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;

public final class Account {
    private final String id;
    private final String currency;
    private final BigDecimal openingBalance;
    private final Instant createdAt;
    private BigDecimal balance;

    public Account(String id, String currency, BigDecimal openingBalance, Instant createdAt) {
        this.id = id;
        this.currency = currency;
        this.openingBalance = openingBalance;
        this.createdAt = createdAt;
        this.balance = openingBalance;
    }

    public String id() { return id; }
    public String currency() { return currency; }
    public BigDecimal openingBalance() { return openingBalance; }
    public Instant createdAt() { return createdAt; }
    public BigDecimal balance() { return balance; }
    public void debit(BigDecimal amount) { balance = balance.subtract(amount); }
    public void credit(BigDecimal amount) { balance = balance.add(amount); }
}
