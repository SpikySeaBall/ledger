#!/usr/bin/env python3
"""Multi-threaded atomicity test for POST /transactions against a running ledger API.

Every scenario releases all worker threads at the same instant (threading.Barrier) so the
requests genuinely race each other on the server. Afterwards the final balances are compared
with the outcome each thread observed, which exposes lost updates, double-applied transfers,
overdrafts and ledger/balance inconsistencies.
"""

import argparse
import json
import random
import sys
import threading
import urllib.error
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
from uuid import uuid4


def call(base_url, method, path, body=None, headers=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request_headers = {"Accept": "application/json"}
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    if headers:
        request_headers.update(headers)
    request_object = urllib.request.Request(f"{base_url}{path}", data=payload, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request_object, timeout=30) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read().decode("utf-8") or "{}")


def expect(base_url, method, path, body=None, headers=None, status=200):
    actual, response = call(base_url, method, path, body, headers)
    assert actual == status, f"{method} {path}: expected {status}, got {actual}: {response}"
    return response


def create_account(base_url, account_id, balance, currency="USD"):
    expect(base_url, "POST", "/accounts",
           {"id": account_id, "currency": currency, "openingBalance": str(balance)}, status=201)


def balance(base_url, account_id):
    return Decimal(str(expect(base_url, "GET", f"/accounts/{account_id}")["balance"]))


def history(base_url, account_id):
    return expect(base_url, "GET", f"/accounts/{account_id}/transactions")


def run_concurrently(jobs, threads):
    """Run (source, destination, amount, key) jobs in parallel, all starting at the same moment."""
    barrier = threading.Barrier(min(threads, len(jobs)))

    def transfer(job):
        source, destination, amount, key = job
        try:
            barrier.wait(timeout=30)
        except threading.BrokenBarrierError:
            pass  # remaining jobs after the first wave just run immediately
        status, body = call(BASE_URL, "POST", "/transactions",
                            {"sourceAccountId": source, "destinationAccountId": destination, "amount": str(amount)},
                            {"Idempotency-Key": key})
        return job, status, body

    with ThreadPoolExecutor(max_workers=threads) as pool:
        return list(pool.map(transfer, jobs))


def assert_equal(actual, expected, message):
    assert actual == expected, f"{message}: expected {expected}, got {actual}"


def scenario_fan_in(threads, suffix):
    """Many sources transfer into ONE destination at the same time: no credit may be lost."""
    destination = f"sink-{suffix}"
    sources = [f"fanin-src{i}-{suffix}" for i in range(20)]
    create_account(BASE_URL, destination, "0.00")
    for source in sources:
        create_account(BASE_URL, source, "1000.00")

    jobs = []
    for source in sources:
        for n in range(15):
            jobs.append((source, destination, Decimal(random.randint(1, 500)) / 100, f"fanin-{suffix}-{source}-{n}"))
    random.shuffle(jobs)
    results = run_concurrently(jobs, threads)

    statuses = Counter(status for _, status, _ in results)
    assert_equal(statuses, Counter({201: len(jobs)}), "fan-in statuses")
    total = sum(job[2] for job in jobs)
    assert_equal(balance(BASE_URL, destination), total, "destination balance (lost credit?)")
    for source in sources:
        sent = sum(job[2] for job in jobs if job[0] == source)
        assert_equal(balance(BASE_URL, source), Decimal("1000.00") - sent, f"{source} balance")
    assert_equal(len(history(BASE_URL, destination)), len(jobs), "destination history length")
    print(f"PASS fan-in: {len(jobs)} concurrent transfers into one account, total {total}")


def scenario_overdraft(threads, suffix):
    """Many concurrent debits from ONE source that cannot cover them all: balance must never go negative."""
    source = f"drain-{suffix}"
    destinations = [f"drain-dst{i}-{suffix}" for i in range(10)]
    create_account(BASE_URL, source, "100.00")
    for destination in destinations:
        create_account(BASE_URL, destination, "0.00")

    amount = Decimal("7.00")
    jobs = [(source, destinations[i % len(destinations)], amount, f"drain-{suffix}-{i}") for i in range(200)]
    results = run_concurrently(jobs, threads)

    statuses = Counter(status for _, status, _ in results)
    succeeded = statuses[201]
    assert_equal(set(statuses), {201, 422}, "overdraft statuses")
    assert_equal(succeeded, 14, "number of successful 7.00 transfers from 100.00")
    assert_equal(balance(BASE_URL, source), Decimal("2.00"), "source balance after draining")
    credited = sum(balance(BASE_URL, d) for d in destinations)
    assert_equal(credited, amount * succeeded, "money received by destinations")
    assert_equal(len(history(BASE_URL, source)), succeeded, "source history length")
    print(f"PASS overdraft: {succeeded} succeeded, {statuses[422]} rejected with 422, balance never negative")


def scenario_bidirectional(threads, suffix):
    """A->B and B->A at the same time: no deadlock, money is conserved, balances match history."""
    a, b = f"ping-{suffix}", f"pong-{suffix}"
    create_account(BASE_URL, a, "1000.00")
    create_account(BASE_URL, b, "1000.00")

    jobs = []
    for i in range(300):
        source, destination = (a, b) if i % 2 == 0 else (b, a)
        jobs.append((source, destination, Decimal(random.randint(1, 2000)) / 100, f"ping-{suffix}-{i}"))
    random.shuffle(jobs)
    results = run_concurrently(jobs, threads)

    statuses = Counter(status for _, status, _ in results)
    assert set(statuses) <= {201, 422}, f"unexpected statuses: {statuses}"
    net_a = Decimal("0")
    for (source, _, amount, _), status, _ in results:
        if status == 201:
            net_a += -amount if source == a else amount
    balance_a, balance_b = balance(BASE_URL, a), balance(BASE_URL, b)
    assert_equal(balance_a, Decimal("1000.00") + net_a, "A balance vs successful transfers")
    assert_equal(balance_a + balance_b, Decimal("2000.00"), "money conserved between A and B")
    assert balance_a >= 0 and balance_b >= 0, "negative balance"
    assert_equal(len(history(BASE_URL, a)), statuses[201], "A history length")
    print(f"PASS bidirectional: {statuses[201]} succeeded, {statuses[422]} rejected, A+B = 2000.00")


def scenario_duplicate_key(threads, suffix):
    """The SAME Idempotency-Key fired concurrently must be applied exactly once."""
    source, destination = f"dup-src-{suffix}", f"dup-dst-{suffix}"
    create_account(BASE_URL, source, "500.00")
    create_account(BASE_URL, destination, "0.00")

    key = f"dup-{suffix}"
    jobs = [(source, destination, Decimal("25.00"), key)] * 100
    results = run_concurrently(jobs, threads)

    statuses = Counter(status for _, status, _ in results)
    assert_equal(statuses, Counter({201: len(jobs)}), "duplicate-key statuses")
    ids = {body["id"] for _, _, body in results}
    assert_equal(len(ids), 1, "distinct transaction ids for one idempotency key")
    assert_equal(balance(BASE_URL, source), Decimal("475.00"), "source debited exactly once")
    assert_equal(balance(BASE_URL, destination), Decimal("25.00"), "destination credited exactly once")
    print(f"PASS duplicate key: {len(jobs)} concurrent replays produced 1 transaction")


def scenario_reconciliation():
    report = expect(BASE_URL, "GET", "/reconciliation/total")
    assert report["balanced"], f"reconciliation discrepancies: {report['discrepancies']}"
    for currency, totals in report["totalsByCurrency"].items():
        assert_equal(Decimal(str(totals["actual"])), Decimal(str(totals["expected"])), f"{currency} total vs ledger")
    print("PASS reconciliation: stored balances match transaction history for every account")


def main():
    global BASE_URL
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--threads", type=int, default=50, help="concurrent client threads (default 50)")
    parser.add_argument("--rounds", type=int, default=1, help="repeat all scenarios N times")
    args = parser.parse_args()
    BASE_URL = args.base_url.rstrip("/")

    try:
        expect(BASE_URL, "GET", "/")
        for round_number in range(1, args.rounds + 1):
            if args.rounds > 1:
                print(f"--- round {round_number}/{args.rounds}")
            suffix = uuid4().hex[:8]
            scenario_fan_in(args.threads, suffix)
            scenario_overdraft(args.threads, suffix)
            scenario_bidirectional(args.threads, suffix)
            scenario_duplicate_key(args.threads, suffix)
            scenario_reconciliation()
    except (AssertionError, KeyError, urllib.error.URLError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("PASS: all concurrency scenarios")
    return 0


BASE_URL = "http://localhost:8000"

if __name__ == "__main__":
    raise SystemExit(main())
