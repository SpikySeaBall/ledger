package com.example.ledger.api;

import com.example.ledger.domain.LedgerTransaction;
import com.example.ledger.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@RestController
public class LedgerController {
    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/")
    public java.util.Map<String, String> status() {
        return java.util.Map.of(
                "service", "ledger-system",
                "status", "running",
                "api", "Use POST /accounts or POST /transactions");
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.AccountResponse createAccount(@Valid @RequestBody AccountRequests.CreateAccount request) {
        return LedgerResponses.AccountResponse.from(ledgerService.createAccount(request));
    }

    @GetMapping("/accounts/{id}")
    public LedgerResponses.AccountResponse getAccount(@PathVariable String id) {
        return LedgerResponses.AccountResponse.from(ledgerService.account(id));
    }

    @GetMapping("/accounts/{id}/transactions")
    public List<LedgerResponses.TransactionResponse> history(@PathVariable String id) {
        return ledgerService.history(id).stream().map(LedgerResponses.TransactionResponse::from).toList();
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.TransactionResponse transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransactionRequests.CreateTransaction request) {
        return LedgerResponses.TransactionResponse.from(ledgerService.createTransfer(request, idempotencyKey));
    }

    @PostMapping("/transactions/{id}/reversal")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerResponses.TransactionResponse reverse(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return LedgerResponses.TransactionResponse.from(ledgerService.reverse(id, idempotencyKey));
    }

    @GetMapping("/reconciliation/total")
    public LedgerResponses.ReconciliationResponse reconcile() {
        return ledgerService.reconcile();
    }

    @PostMapping("/reconciliation/month-end/close")
    public LedgerResponses.MonthCloseResponse closeMonth(@RequestParam YearMonth month) {
        return LedgerResponses.MonthCloseResponse.from(ledgerService.closeMonth(month));
    }

    @GetMapping("/reconciliation/month-end")
    public LedgerResponses.ReconciliationResponse reconcileMonth(@RequestParam YearMonth month) {
        return ledgerService.reconcile(month);
    }
}
