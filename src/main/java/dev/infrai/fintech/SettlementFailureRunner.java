package dev.infrai.fintech;

import java.time.Instant;

public final class SettlementFailureRunner {
    private SettlementFailureRunner() {}

    public static void main(String[] args) throws Exception {
        RuntimeConfig config = RuntimeConfig.fromEnvironment();
        PaymentFailurePolicy policy = new PaymentFailurePolicy(1_000_000L);
        JobFailure failure = new JobFailure(
                "payevt-20261001-0042",
                "daily-settlement",
                "SEPA",
                2_500_000L,
                "EUR",
                RiskTier.HIGH,
                Instant.parse("2026-10-01T01:15:00Z"),
                "Settlement ledger totals did not reconcile",
                "LedgerMismatchException: expected and posted totals differ");

        FailureDecision decision = policy.decide(failure);
        String captureData = new InfraiErrorsClient(config).capture(failure, decision);
        System.out.println("event=" + failure.eventId()
                + " action=" + decision.action()
                + " retry_allowed=" + decision.retryAllowed());
        System.out.println("audit_note=" + decision.auditSummary());
        System.out.println("capture=" + captureData);
    }
}
