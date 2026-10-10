package com.example.ledger.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class TransactionRequests {
    private TransactionRequests() {}

    public record CreateTransaction(
            @NotBlank String sourceAccountId,
            @NotBlank String destinationAccountId,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 18, fraction = 2) BigDecimal amount) {
    }
}
