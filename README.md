# Internal Ledger System

This is a Spring Boot implementation of the ledger assignment. It uses an in-memory store so the project can be run without external infrastructure. The service boundary is intentionally separated from storage and exchange-rate concerns so a database-backed implementation can be introduced later.

For a detailed explanation of how the system works, the technologies used and the reasoning behind the design, see [PROJECT_OVERVIEW.md](PROJECT_OVERVIEW.md).

## Assumptions

- Account balances and transaction amounts use `BigDecimal`; amounts must be positive and are rounded to two decimal places using `HALF_EVEN`.
- An account has one fixed currency. A transfer request's `amount` is the amount debited from the source account.
- Exchange rates are supplied by the `ExchangeRateProvider`. The included provider is deterministic and contains illustrative USD/EUR/GBP/JPY rates.
- `Idempotency-Key` is required for `POST /transactions`. Replaying a key returns the original transaction without applying it again.
- All state-changing operations use one JVM lock. This prevents lost updates for concurrent transfers in this instance; production deployment should replace it with database transactions and row locks.
- Reversals are compensating transactions and never delete or mutate the original transaction. A reversal posts the original amounts swapped (it does not convert again), so a cross-currency reversal restores both accounts exactly.
- A transfer whose converted amount rounds to zero is rejected.
- Reconciliation reports totals per currency; amounts in different currencies are never added together.
- Month-end works in two steps. Closing a finished month (`POST /reconciliation/month-end/close?month=YYYY-MM`) records each account's balance as of the first instant of the next month (UTC), excluding accounts created later. Closing is idempotent. Reconciling that month (`GET /reconciliation/month-end?month=YYYY-MM`) replays the transaction history up to the cutoff and compares it with the recorded closing balances and transaction count, so later activity does not cause false discrepancies, while backdated or missing transactions are reported.

## Run

```bash
mvn spring-boot:run
```

## Run with Docker

Install and start [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/), then run these commands from the project directory:

```powershell
docker compose up --build
```

The first build downloads Maven and Java inside Docker; Maven does not need to be installed on Windows. The API is then available at `http://localhost:8000`. Opening the root URL displays a service status response.

To stop the container:

```powershell
docker compose down
```

## Test every API endpoint

With the Docker application running, execute the Python smoke test from the project directory:

```powershell
python scripts\test_api.py
```

The script in [scripts/test_api.py](scripts/test_api.py) uses only Python's standard library and targets `http://localhost:8000` by default. To test another URL:

```powershell
python scripts\test_api.py --base-url http://localhost:8000
```

## Test concurrent transfers

[scripts/test_concurrency.py](scripts/test_concurrency.py) fires many simultaneous `POST /transactions` requests against the running API. It checks for lost updates, overdrafts, deadlocks between opposite transfers, and duplicate application of one idempotency key, then confirms that reconciliation still balances:

```powershell
python scripts\test_concurrency.py
python scripts\test_concurrency.py --threads 50 --rounds 3 --base-url http://localhost:8000
```

## Python API application

[scripts/ledger_app.py](scripts/ledger_app.py) is a command-line Python application that uses the HTTP API. It uses only the Python standard library. Monetary inputs are validated with `Decimal`, not binary floating-point:

```powershell
python scripts\ledger_app.py status
python scripts\ledger_app.py create-account alice USD 1000
python scripts\ledger_app.py create-account bob EUR 0
python scripts\ledger_app.py balance alice
python scripts\ledger_app.py transfer alice bob 100 --key transfer-001
python scripts\ledger_app.py history alice
python scripts\ledger_app.py reconcile
python scripts\ledger_app.py close-month 2026-09
python scripts\ledger_app.py month-end 2026-09
python scripts\ledger_app.py reverse TRANSACTION_ID --key reversal-001
```

Use `--base-url` before the command to connect to a different API address:

```powershell
python scripts\ledger_app.py --base-url http://localhost:8000 status
```

To run the tests inside Docker without installing Maven locally:

```powershell
docker run --rm -v "${PWD}:/build" -w /build maven:3.9.11-eclipse-temurin-17 mvn test
```

## Example

These commands target `mvn spring-boot:run`, which listens on port 8080. When running with Docker, use port 8000 instead.

```bash
curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"id":"alice","currency":"USD","openingBalance":1000}'

curl -X POST http://localhost:8080/accounts \
  -H "Content-Type: application/json" \
  -d '{"id":"bob","currency":"EUR","openingBalance":0}'

curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: transfer-1" \
  -d '{"sourceAccountId":"alice","destinationAccountId":"bob","amount":100}'
```

## API

- `POST /accounts`
- `GET /accounts/{id}`
- `GET /accounts/{id}/transactions`
- `POST /transactions`
- `POST /transactions/{id}/reversal`
- `GET /reconciliation/total`
- `POST /reconciliation/month-end/close?month=2026-09`
- `GET /reconciliation/month-end?month=2026-09`
