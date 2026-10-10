package com.example.ledger.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public final class AccountRequests {
    private AccountRequests() {}

    public record CreateAccount(
            @NotBlank String id,
            @NotBlank String currency,
            @NotNull @DecimalMin(value = "0.00") @Digits(integer = 18, fraction = 2) BigDecimal openingBalance) {
    }
}
