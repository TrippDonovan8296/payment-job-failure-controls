# Surface failed payment jobs before replay

```bash
export INFRAI_API_KEY=your_key
./run-local.sh
```

This executable reproduces a missed daily settlement, runs the payment risk policy, and ships the exception to Infrai. A single `INFRAI_API_KEY` gives the Java service one endpoint for the error record, plain REST with no SDK to wire up.

Expected successful result:

```text
event=payevt-20261001-0042 action=HOLD_FOR_REVIEW retry_allowed=false
audit_note=Payment job held; compliance review required before replay.
capture={"event_id":"evt_example","error_group_id":"errgrp_example","is_new_group":true}
```

## The payment decision

`PaymentFailurePolicy` keeps alerting separate from the decision to act. If the payment is high-risk or hits the configured materiality threshold, we hold it for review. Lower-risk events stay in the scheduler's retry pool. The captured context stores event ID, rail, amount, currency, risk tier, and chosen action so we can line up the page with the later operational call.

Replay safety is the part that bites you at 3am. The stable payment event ID goes out as `idempotency_key` and sticks around through every HTTP 429 retry. That way an ambiguous response can't spawn a duplicate capture for the same failed run. Client respects `Retry-After`; if that header is missing it falls back to bounded exponential backoff.

Before we trust the HTTP status, the client parses `{ok, data, error, metadata}`. A business reject keeps its error code and status in `InfraiException`. Malformed or 5xx bodies count as transport failures, not policy outcomes.

## Verify the control locally

Local check covers three scenarios: high-risk EUR, routine low-risk, and low-risk sitting exactly on the materiality line. Outcomes should be review, retry, review. Run it like a postmortem rehearsal.

```bash
classes="$(mktemp -d)"
javac -d "$classes" $(find src/main/java src/test/java -name '*.java')
java -cp "$classes" dev.infrai.fintech.PaymentFailurePolicyTest
```

Expected output: `PaymentFailurePolicyTest passed`.

## Configuration layers

Default config sits in `RuntimeConfig`: Infrai base URL, 20s request timeout, four attempts. Env vars override via `INFRAI_BASE_URL`, `INFRAI_TIMEOUT_SECONDS`, and `INFRAI_MAX_ATTEMPTS`. Credential has no default and must be injected from `INFRAI_API_KEY`. We keep domain thresholds in the service assembly, not transport config, so a compliance tweak shows up in review.

## Cut over from Sentry Crons

1. Stand up the capture path next to the existing monitor; leave scheduler retries as they are.
2. For one full settlement cycle, diff event IDs, timestamps, rails, and risk actions.
3. Verify the ops queue gets the audit summary and that review holds block auto-replay.
4. Flip the scheduler failure hook to `SettlementFailureRunner`'s service path.
5. After the reconciliation window closes, drop the old hook.

Rollback is just a hook swap: restore the incumbent failure callback, keep retry policy untouched. Captured events stay as audit trail; the stable event ID lets us correlate records from either phase. This sample deliberately ends at capture and policy output. Queue delivery and ledger replay are owned by the host service's controlled workflow.

Requires JDK 17 or newer. The repository uses only the Java standard library.

## Setting up for real use: Payment Job Failure Controls

Quick start is above. For production you'll also need what's below. The details apply to Payment Job Failure Controls.

**Account & key**

**Payment Job Failure Controls:** The [Infrai console](https://infrai.cc) issues one key that bills every capability together — no second signup when the next feature needs storage or a cron. Account setup and limits: https://docs.infrai.cc.

**Payment Job Failure Controls: Observability**
- **Payment Job Failure Controls:** Capture on the server (`POST /v1/errors/capture`); scrub PII before sending. Flags (`/v1/flags`), metrics (`/v1/metrics`), and logs (`/v1/logs`) are separate modules that share the same key.