#!/usr/bin/env python3
"""Smoke-test every ledger API endpoint against a running Docker application."""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from decimal import Decimal
from datetime import datetime, timedelta, timezone
from uuid import uuid4


TRANSACTION_ID_PATTERN = re.compile(r"^[0-9a-f-]{36}$")


def request(base_url, method, path, body=None, headers=None, expected_status=200):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)

    request_object = urllib.request.Request(
        f"{base_url}{path}",
        data=payload,
        headers=request_headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request_object, timeout=10) as response:
            status = response.status
            response_body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        status = error.code
        response_body = json.loads(error.read().decode("utf-8"))
    except urllib.error.URLError as error:
        raise AssertionError(f"Cannot reach {base_url}: {error.reason}") from error

    assert status == expected_status, f"{method} {path}: expected {expected_status}, got {status}: {response_body}"
    return response_body


def assert_decimal(actual, expected, message):
    assert Decimal(str(actual)) == Decimal(str(expected)), f"{message}: {actual} != {expected}"


def run(base_url):
    suffix = uuid4().hex[:8]
    source_id = f"source-{suffix}"
    destination_id = f"destination-{suffix}"

    status = request(base_url, "GET", "/")
    assert status["status"] == "running"

    source = request(
        base_url,
        "POST",
        "/accounts",
        {"id": source_id, "currency": "USD", "openingBalance": 100},
        expected_status=201,
    )
    assert source["id"] == source_id

    destination = request(
        base_url,
        "POST",
        "/accounts",
        {"id": destination_id, "currency": "EUR", "openingBalance": 0},
        expected_status=201,
    )
    assert destination["currency"] == "EUR"

    source_balance = request(base_url, "GET", f"/accounts/{source_id}")
    assert_decimal(source_balance["balance"], "100.00", "opening source balance")

    transaction = request(
        base_url,
        "POST",
        "/transactions",
        {"sourceAccountId": source_id, "destinationAccountId": destination_id, "amount": 10},
        headers={"Idempotency-Key": f"transfer-{suffix}"},
        expected_status=201,
    )
    assert TRANSACTION_ID_PATTERN.fullmatch(transaction["id"]), "transaction ID is not a UUID"

    replay = request(
        base_url,
        "POST",
        "/transactions",
        {"sourceAccountId": source_id, "destinationAccountId": destination_id, "amount": 10},
        headers={"Idempotency-Key": f"transfer-{suffix}"},
        expected_status=201,
    )
    assert replay["id"] == transaction["id"], "idempotent replay created another transaction"

    history = request(base_url, "GET", f"/accounts/{source_id}/transactions")
    assert len(history) == 1 and history[0]["id"] == transaction["id"]

    source_after_transfer = request(base_url, "GET", f"/accounts/{source_id}")
    assert_decimal(source_after_transfer["balance"], "90.00", "source balance after transfer")

    reversal = request(
        base_url,
        "POST",
        f"/transactions/{transaction['id']}/reversal",
        headers={"Idempotency-Key": f"reversal-{suffix}"},
        expected_status=201,
    )
    assert reversal["type"] == "REVERSAL"
    assert reversal["reversalOf"] == transaction["id"]

    total = request(base_url, "GET", "/reconciliation/total")
    assert total["balanced"], f"reconciliation discrepancies: {total['discrepancies']}"
    assert {"USD", "EUR"} <= set(total["totalsByCurrency"]), "totals are not reported per currency"

    today = datetime.now(timezone.utc)
    current_month = today.strftime("%Y-%m")
    previous_month = (today.replace(day=1) - timedelta(days=1)).strftime("%Y-%m")
    request(base_url, "POST", f"/reconciliation/month-end/close?month={current_month}", expected_status=409)
    close = request(base_url, "POST", f"/reconciliation/month-end/close?month={previous_month}")
    assert source_id not in close["closingBalances"], "account created this month included in last month's close"
    month_end = request(base_url, "GET", f"/reconciliation/month-end?month={previous_month}")
    assert month_end["balanced"], f"month-end discrepancies: {month_end['discrepancies']}"

    restored_source = request(base_url, "GET", f"/accounts/{source_id}")
    assert_decimal(restored_source["balance"], "100.00", "source balance after reversal")
    print("PASS: all API endpoints and core behaviours")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8000")
    args = parser.parse_args()
    try:
        run(args.base_url.rstrip("/"))
    except (AssertionError, KeyError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
