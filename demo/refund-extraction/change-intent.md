# Extract refunds from the payment monolith

Move refund processing behind a service boundary and add partial refunds.

Preserve the existing cumulative refund cap and operation-key semantics.
A new partial-refund path must use the same remaining balance as full refunds.

Proposed acceptance checks:
- Accept 2500 against a 10000 capture.
- After refunding 8000, reject another 3000.

This fixture models the domain transition only. It does not implement HTTP,
distributed transactions, a real provider, or a production service extraction.
