package com.example.ledger.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class FixedExchangeRateProvider implements ExchangeRateProvider {
    private static final Map<String, BigDecimal> USD_RATES = Map.of(
            "USD", new BigDecimal("1.00"),
            "EUR", new BigDecimal("0.92"),
            "GBP", new BigDecimal("0.79"),
            "JPY", new BigDecimal("149.50"));

    @Override
    public BigDecimal rate(String sourceCurrency, String destinationCurrency) {
        BigDecimal sourceRate = USD_RATES.get(sourceCurrency);
        BigDecimal destinationRate = USD_RATES.get(destinationCurrency);
        if (sourceRate == null || destinationRate == null) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "Unsupported currency");
        }
        if (sourceCurrency.equals(destinationCurrency)) {
            return BigDecimal.ONE;
        }
        return BigDecimal.ONE.divide(sourceRate, 10, java.math.RoundingMode.HALF_EVEN)
                .multiply(destinationRate).setScale(10, java.math.RoundingMode.HALF_EVEN);
    }
}
