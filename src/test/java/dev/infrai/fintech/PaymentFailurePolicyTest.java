package dev.infrai.fintech;

import java.time.Instant;

public final class PaymentFailurePolicyTest {
    public static void main(String[] args) {
        PaymentFailurePolicy policy = new PaymentFailurePolicy(1_000_000L);

        FailureDecision highRisk = policy.decide(failure("high-1", 25_000L, RiskTier.HIGH));
        check(!highRisk.retryAllowed(), "high-risk payment must not be replayed automatically");
        check("HOLD_FOR_REVIEW".equals(highRisk.action()), "high-risk payment must enter review");

        FailureDecision routine = policy.decide(failure("low-1", 25_000L, RiskTier.LOW));
        check(routine.retryAllowed(), "low-risk payment should remain retryable");
        check("RETRY_ALLOWED".equals(routine.action()), "low-risk action must be explicit");

        FailureDecision material = policy.decide(failure("material-1", 1_000_000L, RiskTier.LOW));
        check(!material.retryAllowed(), "material amount must enter review regardless of risk tier");
        System.out.println("PaymentFailurePolicyTest passed");
    }

    private static JobFailure failure(String id, long amountMinor, RiskTier riskTier) {
        return new JobFailure(id, "daily-settlement", "SEPA", amountMinor, "EUR", riskTier,
                Instant.parse("2026-10-01T01:15:00Z"), "ledger mismatch", "LedgerMismatchException");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
