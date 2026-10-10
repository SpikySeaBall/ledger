#!/usr/bin/env python3
"""Command-line client for the Dockerized ledger API."""

import argparse
import json
import sys
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation
from uuid import uuid4


class ApiError(RuntimeError):
    pass


class LedgerApi:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def call(self, method, path, payload=None, headers=None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request_headers = {"Accept": "application/json"}
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        if headers:
            request_headers.update(headers)

        request = urllib.request.Request(
            f"{self.base_url}{path}",
            data=body,
            headers=request_headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            try:
                details = json.loads(error.read().decode("utf-8"))
            except json.JSONDecodeError:
                details = error.reason
            raise ApiError(f"{error.code}: {details}") from error
        except urllib.error.URLError as error:
            raise ApiError(
                f"Cannot connect to {self.base_url}. Is Docker running? ({error.reason})"
            ) from error

    def status(self):
        return self.call("GET", "/")

    def create_account(self, account_id, currency, opening_balance):
        return self.call(
            "POST",
            "/accounts",
            {
                "id": account_id,
                "currency": currency.upper(),
                "openingBalance": opening_balance,
            },
        )

    def account(self, account_id):
        return self.call("GET", f"/accounts/{account_id}")

    def history(self, account_id):
        return self.call("GET", f"/accounts/{account_id}/transactions")

    def transfer(self, source, destination, amount, idempotency_key):
        return self.call(
            "POST",
            "/transactions",
            {
                "sourceAccountId": source,
                "destinationAccountId": destination,
                "amount": amount,
            },
            {"Idempotency-Key": idempotency_key},
        )

    def reverse(self, transaction_id, idempotency_key):
        return self.call(
            "POST",
            f"/transactions/{transaction_id}/reversal",
            headers={"Idempotency-Key": idempotency_key},
        )

    def reconcile(self):
        return self.call("GET", "/reconciliation/total")

    def close_month(self, month):
        return self.call("POST", f"/reconciliation/month-end/close?month={month}")

    def reconcile_month(self, month):
        return self.call("GET", f"/reconciliation/month-end?month={month}")


def positive_amount(value):
    try:
        amount = Decimal(value)
    except (InvalidOperation, ValueError) as error:
        raise argparse.ArgumentTypeError("amount must be a number") from error
    if not amount.is_finite() or amount <= 0:
        raise argparse.ArgumentTypeError("amount must be greater than zero")
    return value


def build_parser():
    parser = argparse.ArgumentParser(
        description="Use the ledger system API running in Docker."
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost:8000",
        help="API base URL (default: http://localhost:8000)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("status", help="check whether the API is running")

    create = commands.add_parser("create-account", help="create an account")
    create.add_argument("account_id")
    create.add_argument("currency")
    create.add_argument("opening_balance", type=positive_amount)

    balance = commands.add_parser("balance", help="show an account balance")
    balance.add_argument("account_id")

    history = commands.add_parser("history", help="show account transactions")
    history.add_argument("account_id")

    transfer = commands.add_parser("transfer", help="transfer money")
    transfer.add_argument("source_account")
    transfer.add_argument("destination_account")
    transfer.add_argument("amount", type=positive_amount)
    transfer.add_argument(
        "--key",
        default=None,
        help="idempotency key (generated automatically if omitted)",
    )

    reverse = commands.add_parser("reverse", help="reverse a transaction")
    reverse.add_argument("transaction_id")
    reverse.add_argument(
        "--key",
        default=None,
        help="idempotency key (generated automatically if omitted)",
    )

    commands.add_parser("reconcile", help="reconcile all current balances")

    close_month = commands.add_parser("close-month", help="record closing balances for a finished month")
    close_month.add_argument("month", help="month in YYYY-MM format")

    month_end = commands.add_parser("month-end", help="reconcile a closed month against transaction history")
    month_end.add_argument("month", help="month in YYYY-MM format")
    return parser


def execute(args, api):
    if args.command == "status":
        return api.status()
    if args.command == "create-account":
        return api.create_account(args.account_id, args.currency, args.opening_balance)
    if args.command == "balance":
        return api.account(args.account_id)
    if args.command == "history":
        return api.history(args.account_id)
    if args.command == "transfer":
        return api.transfer(
            args.source_account,
            args.destination_account,
            args.amount,
            args.key or f"python-client-{uuid4()}",
        )
    if args.command == "reverse":
        return api.reverse(args.transaction_id, args.key or f"python-client-{uuid4()}")
    if args.command == "reconcile":
        return api.reconcile()
    if args.command == "close-month":
        return api.close_month(args.month)
    if args.command == "month-end":
        return api.reconcile_month(args.month)
    raise ApiError(f"Unknown command: {args.command}")


def main():
    args = build_parser().parse_args()
    try:
        result = execute(args, LedgerApi(args.base_url))
    except ApiError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
