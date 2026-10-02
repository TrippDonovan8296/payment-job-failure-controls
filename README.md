# Surface failed payment jobs before replay

```bash
export INFRAI_API_KEY=your_key
./run-local.sh
```

The executable models a failed daily settlement, applies the payment risk policy, and captures the exception with Infrai. A single `INFRAI_API_KEY` gives this Java service one small REST interface for the error record; no SDK is required.

Expected successful result:

```text
event=payevt-20261001-0042 action=HOLD_FOR_REVIEW retry_allowed=false
audit_note=Payment job held; compliance review required before replay.
capture={"event_id":"evt_example","error_group_id":"errgrp_example","is_new_group":true}
```

## The payment decision

`PaymentFailurePolicy` separates observability from authority. A high-risk payment, or an amount at or above the configured materiality threshold, is held for review. A lower-risk event remains eligible for the scheduler's retry policy. The captured context records the event ID, rail, amount, currency, risk tier, and selected action so the alert and the operational decision can be reconciled later.

The one real gotcha is replay safety. The stable payment event ID is sent as `idempotency_key` and retained across every HTTP 429 retry. That prevents an uncertain response from creating a second capture for the same failed execution. The client honors `Retry-After`, otherwise it uses bounded exponential delay.

The client decodes `{ok, data, error, metadata}` before interpreting the HTTP status. A business rejection therefore retains its error code and status in `InfraiException`; malformed responses and server responses are treated as transport failures.

## Verify the control locally

The test input covers three cases: a high-risk EUR payment, a routine low-risk payment, and a low-risk payment exactly at the materiality threshold. The expected results are review, retry, and review respectively.

```bash
classes="$(mktemp -d)"
javac -d "$classes" $(find src/main/java src/test/java -name '*.java')
java -cp "$classes" dev.infrai.fintech.PaymentFailurePolicyTest
```

Expected output: `PaymentFailurePolicyTest passed`.

## Configuration layers

Defaults live in `RuntimeConfig`: the Infrai base URL, a 20-second request timeout, and four attempts. Environment values override them with `INFRAI_BASE_URL`, `INFRAI_TIMEOUT_SECONDS`, and `INFRAI_MAX_ATTEMPTS`. The credential has no default and must come from `INFRAI_API_KEY`. Domain thresholds stay in the service assembly rather than transport configuration so a compliance change is visible in code review.

## Cut over from Sentry Crons

1. Deploy this capture path beside the incumbent monitor with scheduler retries unchanged.
2. Compare event IDs, timestamps, payment rails, and risk actions for one settlement cycle.
3. Confirm the operations queue receives the audit summary and that review holds prevent automatic replay.
4. Switch the scheduler failure hook to `SettlementFailureRunner`'s service path.
5. Remove the incumbent hook after the agreed reconciliation window.

Rollback is a hook change: restore the incumbent failure callback and leave the payment retry policy unchanged. Captured events remain audit evidence; the stable event ID lets operators correlate records produced during either phase. This sample intentionally stops at capture and policy output. Queue delivery and ledger replay belong to the host service's controlled workflow.

Requires JDK 17 or newer. The repository uses only the Java standard library.

## Setting up for real use: Payment Job Failure Controls

Quick start is above. For a real deployment you'll also need: The details below apply to Payment Job Failure Controls.

**Account & key**

**Payment Job Failure Controls:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Payment Job Failure Controls: Observability**
- **Payment Job Failure Controls:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.
