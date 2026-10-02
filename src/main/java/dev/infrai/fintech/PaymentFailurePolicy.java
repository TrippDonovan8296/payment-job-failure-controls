package dev.infrai.fintech;

import java.time.Instant;
import java.util.Objects;

enum RiskTier { LOW, MEDIUM, HIGH }

record JobFailure(
        String eventId,
        String jobName,
        String paymentRail,
        long amountMinor,
        String currency,
        RiskTier riskTier,
        Instant failedAt,
        String reason,
        String exceptionText) {
    JobFailure {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(jobName);
        Objects.requireNonNull(paymentRail);
        Objects.requireNonNull(currency);
        Objects.requireNonNull(riskTier);
        Objects.requireNonNull(failedAt);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(exceptionText);
        if (amountMinor <= 0) throw new IllegalArgumentException("amountMinor must be positive");
    }
}

record FailureDecision(String action, boolean retryAllowed, String auditSummary) {}

public final class PaymentFailurePolicy {
    private final long manualReviewThresholdMinor;

    public PaymentFailurePolicy(long manualReviewThresholdMinor) {
        this.manualReviewThresholdMinor = manualReviewThresholdMinor;
    }

    public FailureDecision decide(JobFailure failure) {
        boolean sensitive = failure.riskTier() == RiskTier.HIGH
                || failure.amountMinor() >= manualReviewThresholdMinor;
        if (sensitive) {
            return new FailureDecision(
                    "HOLD_FOR_REVIEW",
                    false,
                    "Payment job held; compliance review required before replay.");
        }
        return new FailureDecision(
                "RETRY_ALLOWED",
                true,
                "Payment job may be replayed under the scheduler retry policy.");
    }
}
