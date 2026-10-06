<!-- undertow-review:v1 -->
# Undertow change-risk review

**Status: partial** · Advisory

Base: c80301580713505d2025b27fec0370b9f10ec515  
Head: 1533b438ed88a9c7add667305e4f333b1bd760ea

Authored offline replay for retry-key-unsafe; live model quality is not measured.

## JAVA-RETRY-001: retry-key-unsafe

**Risk:** CRITICAL · **Category:** DATA_INTEGRITY · **Confidence:** HIGH

Location: src/Subject.java:6 at 1533b438ed88a9c7add667305e4f333b1bd760ea

Trigger: Gateway commits a payment, loses the response and the caller retries.

Changed behavior: The changed expression replaces the baseline contract-preserving behavior.

Consequence: A new key represents a new charge and can duplicate customer debit. The declared order/payment invariant may fail.

Evidence: e1, e2, e3, e4, e5 (INFERRED)

Confidence: The designed fixture has an explicit contract and a causal edit. This is authored replay output.

Rules: JAVA-RETRY-001 · BLOCKER

Assumptions: Collaborators obey the fixture contract; symbol resolution is unavailable.

Recommended change: Restore the contract-preserving baseline behavior.

- Regression (integration, proposed): Controlled gateway/repository and explicit decimal strings. → Gateway commits a payment, loses the response and the caller retries. → Drop the first response; assert equal keys and one charge.

## Coverage gaps

- Classpath symbols in this synthetic fixture are unresolved.

Model: offline-replay-v1 · Calls: 2 model / 5 tools · Elapsed: 152 ms
