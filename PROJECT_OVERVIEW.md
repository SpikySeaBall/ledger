# Internal Ledger System — Project Overview

This document explains what the ledger does, how it works internally, which technologies it uses, and why the main design decisions were made. It is a companion to `README.md`, which covers running the service. The original brief is in `ledger-system-assignment.md`.

---

## 1. What the project does

The project is a small financial ledger exposed as a REST API. It:

- holds **accounts**, each with one currency and a running balance;
- records **transfers** between accounts. A transfer is atomic, so both sides are updated or neither is;
- **converts currencies** when the two accounts use different currencies;
- **rejects overdrafts**, so a transfer that would make the source balance negative is refused;
- makes transfers **idempotent**, so a request retried with the same `Idempotency-Key` is applied only once;
- **reverses** a transfer by posting a new compensating transaction. History is never edited or deleted;
- **reconciles** the ledger by replaying the transaction history and comparing the result with stored balances, with totals reported per currency;
- **closes a month** by freezing each account's closing balance. It can later check that closed month against the transaction history to find backdated or missing entries.

### Requirement coverage

| Requirement (from the assignment) | Where it is satisfied | Verified by |
|---|---|---|
| `POST /transactions` records a transfer and returns its ID and a confirmation | `LedgerController.transfer` → `LedgerService.createTransfer` returns `201 Created` with the full transaction (UUID `id`, amounts, rate, timestamp) | `test_api.py` |
| Atomic: both accounts are updated or neither is | `LedgerService.postTransfer`: every validation runs before either balance changes, and the whole operation holds the ledger lock | Unit tests, `test_concurrency.py` |
| `GET /accounts/{id}` returns the balance | `LedgerController.getAccount` | `test_api.py` |
| `GET /accounts/{id}/transactions` returns history ordered by timestamp | `LedgerService.history` (sorted by `timestamp`) | `test_api.py`, edge-case probe |
| Source decreases and destination increases by the same amount | `postTransfer` debits `sourceAmount` and credits `destinationAmount`, which is the same value when the currencies match | Unit tests |
| Insufficient balance is rejected | `postTransfer` → `422 Unprocessable Entity` | Unit tests, overdraft scenario |
| Reversal is posted as a correction transaction | `POST /transactions/{id}/reversal` → `LedgerService.reverse` | Unit tests, `test_api.py` |
| Repeated submissions do not create extra transfers | Required `Idempotency-Key` header on transfers (optional on reversals); a reversal also cannot be applied twice | Unit tests, duplicate-key scenario (100 concurrent replays → 1 transaction) |
| Total money is checked against the expected sum and discrepancies are reported | `GET /reconciliation/total` → `LedgerService.reconcile()` | Unit tests, all scripts |
| Cross-currency transfers use exchange rates | `ExchangeRateProvider` / `FixedExchangeRateProvider` | Unit tests, edge-case probe |
| Concurrent transfers to the same account lose no updates | One ledger-wide lock around every write | Fan-in, overdraft and bidirectional scenarios (300 concurrent requests each, 3 rounds) |
| Month-end reconciliation against transaction history | `POST /reconciliation/month-end/close` and `GET /reconciliation/month-end` | Unit tests (including backdated-entry detection), `test_api.py` |

### Verification performed

- **Unit tests** (`mvn test`, run in the `maven:3.9.11-eclipse-temurin-17` container): **13/13 pass**.
- **API smoke test** (`scripts/test_api.py`) against a freshly built Docker image: **pass**.
- **Concurrency test** (`scripts/test_concurrency.py --rounds 3`, 50 client threads): **all scenarios pass in every round**.
- **Additional edge-case probe** (22 checks: duplicate account, negative opening balance, unsupported currency, missing idempotency key, zero or 3-decimal amounts, same-account transfer, unknown account or transaction, FX amounts, reversal replay, double reversal, reversing a reversal, history ordering, malformed month): **all pass**.

---

## 2. Architecture

```
            HTTP (JSON)
                │
┌───────────────▼────────────────┐
│  api/                          │  LedgerController       routes, status codes
│                                │  *Requests / *Responses  DTOs + bean validation
│                                │  ApiExceptionHandler     errors → JSON
└───────────────┬────────────────┘
                │
┌───────────────▼────────────────┐
│  service/                      │  LedgerService          business rules, locking,
│                                │                          idempotency, reconciliation
│                                │  ExchangeRateProvider   (interface)
│                                │  FixedExchangeRateProvider (implementation)
│                                │  LedgerException        domain error + HTTP status
└───────────────┬────────────────┘
                │
┌───────────────▼────────────────┐
│  domain/                       │  Account (mutable balance)
│                                │  LedgerTransaction (immutable record)
│                                │  MonthClose (immutable snapshot)
│                                │  TransactionType { TRANSFER, REVERSAL }
└────────────────────────────────┘
```

The code is split into three layers:

- **`api`** handles HTTP only. It validates request shape, maps domain objects to response records and converts exceptions to JSON errors. It holds no business logic.
- **`service`** holds all ledger rules. It does not depend on HTTP details apart from tagging each `LedgerException` with the status code that best describes the failure.
- **`domain`** contains plain Java types with no framework annotations.

### Data held in memory (`LedgerService`)

| Map | Key → Value | Purpose |
|---|---|---|
| `accounts` | account id → `Account` | current balances |
| `transactions` | transaction id → `LedgerTransaction` | append-only journal |
| `idempotencyToTransaction` | idempotency key → transaction id | replay detection |
| `monthCloses` | `YearMonth` → `MonthClose` | frozen month-end snapshots |

---

## 3. How it works

### 3.1 Creating an account — `POST /accounts`

```json
{ "id": "alice", "currency": "USD", "openingBalance": 1000 }
```

1. Bean validation checks that the id and currency are present and that the opening balance is ≥ 0 with at most 2 decimal places.
2. Under the ledger lock, the service rejects duplicate ids (`409`), normalises the currency to upper case and checks that it is supported. It does this by asking the rate provider for a same-currency rate, which throws `400` for unknown currencies.
3. The opening balance is stored separately from the running balance because reconciliation replays history starting from it.

### 3.2 Transferring money — `POST /transactions`

```
Idempotency-Key: transfer-001
{ "sourceAccountId": "alice", "destinationAccountId": "bob", "amount": 100 }
```

`LedgerService.createTransfer` runs these steps **inside one `synchronized` block**:

1. **Idempotency check.** If the key has been seen before, the original transaction is returned unchanged. The client gets the same `201` and the same `id`, and balances do not change.
2. **Lookup.** Both accounts must exist (`404`).
3. **Amount validation.** The amount must be positive with at most 2 decimals (`400`).
4. **Currency conversion.** `rate = provider.rate(src, dst)`, then `destinationAmount = amount × rate`, rounded HALF_EVEN to 2 dp. If the converted amount rounds to zero, the transfer is rejected (`400`). Otherwise money would leave one account and never arrive in the other.
5. **`postTransfer`.** It checks that source ≠ destination (`400`) and that the source has enough funds (`422`). Only after **every** check passes does it debit the source, credit the destination, append the immutable `LedgerTransaction` and record the idempotency key.

Every check runs before the first mutation, and the lock stops anything else from seeing a half-applied transfer. That gives the all-or-nothing behaviour without a database transaction.

The stored transaction records both amounts, both currencies and the exact rate used. The history is therefore self-describing and can be audited even if the rates change later.

### 3.3 Exchange rates

`FixedExchangeRateProvider` keeps rates against USD (USD 1.00, EUR 0.92, GBP 0.79, JPY 149.50). It derives cross rates as `(1 / sourceRate) × destinationRate` at 10 dp precision. The request's `amount` is always in the **source** currency, because that is the amount the customer authorises to leave their account.

### 3.4 Reversals — `POST /transactions/{id}/reversal`

A reversal is a **new** transaction of type `REVERSAL` with `reversalOf = <original id>`. Its direction is the opposite of the original.

- It posts the original **amounts swapped exactly**. It does not convert the money again. Converting again would round a second time, and a cross-currency reversal could then return a different amount from what was taken. For example, USD 0.07 → GBP 0.06 is reversed as GBP 0.06 → USD 0.07.
- A transaction can be reversed only once (`409`), and a reversal cannot itself be reversed (`409`).
- The reversal goes through the same `postTransfer` path, so it is also atomic and balance-checked. If the destination has already spent the funds, the reversal is refused with `422` rather than making the balance negative.
- The `Idempotency-Key` is optional on reversals. The "only once" rule already makes a retried reversal harmless; the key just lets a retry receive the original response instead of a `409`.

### 3.5 Reconciliation — `GET /reconciliation/total`

For every account, the service computes:

```
expected = openingBalance − Σ sourceAmount (as source) + Σ destinationAmount (as destination)
```

It compares this with the stored `balance`. Any mismatch is listed in `discrepancies`. Actual and expected totals are reported **per currency**, because adding USD to JPY would produce a meaningless number. Under correct operation the response is `balanced: true`.

### 3.6 Month-end — close, then reconcile

**Close** (`POST /reconciliation/month-end/close?month=2026-09`):

- The cutoff is the first instant of the following month in UTC. A month cannot be closed before it has ended (`409`).
- For each account created before the cutoff, the closing balance is `current balance − net postings at or after the cutoff`. A close run on 2 October therefore still captures the balance as at 30 September.
- The number of transactions dated before the cutoff is recorded as well.
- Closing is **idempotent**. Closing the same month again returns the original snapshot, so a closed month cannot be quietly rewritten.

**Reconcile** (`GET /reconciliation/month-end?month=2026-09`):

- It replays the transaction history **up to the cutoff** and compares the result with the frozen closing balances and transaction count.
- Activity after the cutoff is ignored, so normal October activity does not produce false alarms.
- A transaction dated **inside** the closed month but posted after the close (a backdated entry) changes the replayed balance and the count, so it is reported. The unit test `monthEndReconciliationDetectsBackdatedTransaction` demonstrates this.
- Reconciling a month that has not been closed returns `404`.

### 3.7 Errors

All errors share one JSON shape:

```json
{ "timestamp": "2026-10-11T09:00:00Z", "status": 422, "error": "Insufficient balance" }
```

| Status | Meaning |
|---|---|
| 400 | malformed input, missing idempotency key, unsupported currency, amount converts to zero, same-account transfer |
| 404 | unknown account, transaction, or unclosed month |
| 409 | duplicate account, transaction already reversed, reversing a reversal, closing an unfinished month |
| 422 | insufficient balance |

---

## 4. Technologies used

| Technology | Role | Why |
|---|---|---|
| **Java 17** | Language | Preferred by the brief. As an LTS release it provides `record` types (immutable DTOs and domain objects with little code), `switch`/stream improvements and a strong concurrency library. |
| **Spring Boot 3.5** (`spring-boot-starter-web`) | HTTP server, routing, JSON (Jackson), dependency injection | The standard way to build a Java REST service. Embedded Tomcat avoids any app-server setup, and constructor injection keeps `LedgerService` testable without Spring. |
| **Jakarta Bean Validation** (`spring-boot-starter-validation`) | Request validation (`@NotBlank`, `@DecimalMin`, `@Digits`) | Rejects malformed requests at the boundary in a declarative way, before they reach business logic. |
| **`java.math.BigDecimal`** | All money values | Binary floating point cannot represent values such as 0.10 exactly. `BigDecimal` with an explicit scale and rounding mode gives exact decimal arithmetic. |
| **`java.time` (`Instant`, `YearMonth`, `Clock`)** | Timestamps and month boundaries | Time zones are explicit (UTC). An injected `Clock` lets tests move time to exercise month-end logic deterministically. |
| **JUnit 5** (`spring-boot-starter-test`) | Unit and concurrency tests | Standard and already bundled. Tests construct `LedgerService` directly, so they run in well under a second. |
| **Maven** | Build | The conventional Java build tool. The Spring Boot parent POM manages dependency versions. |
| **Docker / Docker Compose** | Packaging and running | A multi-stage build compiles inside a Maven image and runs on a slim JRE image, so reviewers need neither Java nor Maven installed. |
| **Python 3 (standard library only)** | `test_api.py`, `test_concurrency.py`, `ledger_app.py` CLI | Black-box tests over real HTTP that need no `pip install`. The CLI uses `Decimal` for money input, for the same reason the server uses `BigDecimal`. |

---

## 5. Design decisions and justifications

### 5.1 Append-only journal with a cached balance
Transactions are immutable records that are never updated or deleted. Corrections are made with reversals. This is how real ledgers work: the audit trail is complete, and any balance can be rebuilt from history. Each `Account` still keeps a running `balance` so that `GET /accounts/{id}` does not need a full replay. Reconciliation then checks that the cached value and the replayed value agree.

### 5.2 One ledger-wide lock instead of per-account locks
Every state-changing operation, and both reconciliations, runs inside `synchronized (ledgerLock)`.

- **Correct by construction.** No lost updates, no overdraft races, and no window in which a reader could see the debit without the credit.
- **No deadlocks.** Per-account locking needs a global lock order to avoid A→B / B→A deadlocks. With one lock, that whole class of bug cannot happen. The bidirectional concurrency test checks this.
- **Atomic idempotency.** The "check key, then post" sequence cannot interleave, so 100 simultaneous requests with the same key produce exactly one transaction.
- **Trade-off:** throughput is serialised. That is acceptable for an in-memory service whose operations take microseconds. A production version would move this guarantee into the database (see §6).

The backing maps are still `ConcurrentHashMap`, so lock-free reads such as `history()` see a consistent map structure.

### 5.3 Validate everything, then mutate
`postTransfer` runs every check before it touches a balance. Even without a database rollback, a rejected transfer leaves no partial effects.

### 5.4 Idempotency key required on transfers
Making `Idempotency-Key` **mandatory** on `POST /transactions` means a client cannot accidentally send a transfer that is unsafe to retry. Requests that fail validation are not stored against the key, so a client can fix the request and retry with the same key. On reversals the key is optional, because "a transaction can be reversed only once" already makes repeated reversals safe.

### 5.5 Money rules
- Money is held at a fixed scale of 2 dp. Inputs with more decimals are **rejected** rather than silently rounded, so the ledger never records an amount the client did not send.
- Conversion results use banker's rounding (HALF_EVEN), which avoids systematic upward bias across many transactions.
- Transfers that round to zero after conversion are rejected.
- Reversals reuse the stored amounts instead of converting again (§3.4).

### 5.6 Each account has one currency; totals are kept per currency
Mixed-currency accounts would need multiple balance buckets and would make overdraft rules ambiguous. With one currency per account, a cross-currency transfer debits one currency and credits another. Converted money is not "conserved" across currencies in any meaningful sense, so reconciliation checks conservation **per account against history** and reports totals per currency, never summed across currencies.

### 5.7 Exchange rates behind an interface
`ExchangeRateProvider` separates rate sourcing from ledger logic. The fixed provider makes behaviour and tests deterministic. A live rate feed or a rates table could be plugged in without touching `LedgerService`. Recording the rate on each transaction keeps history correct when rates change.

### 5.8 Two-step month-end
A single "reconcile month" call that compares current balances with history would flag every account that had activity after month-end. Freezing a snapshot at close and later replaying history only up to the cutoff separates the two questions: *what did we say the balance was?* and *what does the history now say it was?* A difference means history inside a closed period has changed, which is exactly what month-end controls are meant to catch.

### 5.9 Injectable `Clock`
All timestamps come from an injected `java.time.Clock`, which is UTC in production. Tests swap in a controllable clock to post transactions in specific months, close months, and simulate backdated entries without sleeping or depending on today's date.

### 5.10 In-memory storage
The brief targets correctness and clarity, and an in-memory store means the project runs with a single command and no external infrastructure. The service layer is the only code that touches the maps, so swapping in a repository backed by a relational database is a local change.

---

## 6. Known limitations and production path

These are deliberate scope limits for a take-home project, not defects in the required behaviour:

1. **No persistence.** All data is lost when the process restarts. For production, use PostgreSQL with `transactions` as an append-only table and an `accounts` balance column. Wrap each transfer in a DB transaction that locks both account rows with `SELECT … FOR UPDATE` in a consistent id order, and put a `UNIQUE` constraint on the idempotency key.
2. **Single instance.** The JVM lock protects only one process. Running several replicas would need the database-level locking above.
3. **Idempotency keys are not tied to a request body.** Reusing a key with a *different* payload returns the original transaction instead of an error. Transfers and reversals also share one key namespace. A stricter implementation would store a hash of the request and return `422` on a mismatch.
4. **Static exchange rates.** These are illustrative values only.
5. **History ordering on identical timestamps.** History is sorted by timestamp. Two transactions with exactly the same `Instant` have no defined order between them. A monotonic sequence number would make the order fully deterministic.
6. **Unlocked balance reads.** `GET /accounts/{id}` reads the balance field without taking the lock. Writes are never lost, but the value can be momentarily stale. Making `balance` `volatile`, or reading under the lock, would guarantee read-your-writes visibility.
7. **No authentication or authorisation.** These are out of scope for the brief.

---

## 7. API reference

| Method | Path | Body / params | Success |
|---|---|---|---|
| GET | `/` | — | `200` service status |
| POST | `/accounts` | `{id, currency, openingBalance}` | `201` account |
| GET | `/accounts/{id}` | — | `200` account with balance |
| GET | `/accounts/{id}/transactions` | — | `200` transactions, oldest first |
| POST | `/transactions` | header `Idempotency-Key`; `{sourceAccountId, destinationAccountId, amount}` | `201` transaction |
| POST | `/transactions/{id}/reversal` | optional header `Idempotency-Key` | `201` reversal transaction |
| GET | `/reconciliation/total` | — | `200` `{balanced, totalsByCurrency, discrepancies}` |
| POST | `/reconciliation/month-end/close` | `?month=YYYY-MM` | `200` month-close snapshot |
| GET | `/reconciliation/month-end` | `?month=YYYY-MM` | `200` `{balanced, totalsByCurrency, discrepancies}` |
