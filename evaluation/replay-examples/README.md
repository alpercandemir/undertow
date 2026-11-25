# Three regressions that pass the existing suite

These examples use synthetic data and deliberately introduced regressions. Each compares two Git commits with the same existing tests. [Run the examples](../../README.md#run-the-examples)

| What changed | Input Undertow found and what happened | Reproduce or inspect |
| --- | --- | --- |
| **A $40 refund disappears.** A webhook refactor deduplicates by payment and amount. | `[1, 1]` creates two separate one-item refunds on a $120 payment, with a retry of the first webhook in between. The base records both refunds. The candidate treats the second as another retry and records **$40 instead of $80**. | [Patch](refunds/candidate.patch) · [Report](results/refunds/report.json) · [New test](results/refunds/regression.py) |
| **Three invoices are skipped after a restart.** An exporter saves the end of a batch as its checkpoint. | `1` lets the finance inbox acknowledge only the first of four invoices before the worker restarts. The base resumes at the second invoice. The candidate reports the same final checkpoint as the base, with **one invoice delivered instead of four**. | [Patch](invoice-export/candidate.patch) · [Report](results/invoice-export/report.json) · [New test](results/invoice-export/regression.py) |
| **A ferry disappears an hour before departure.** The board starts filtering by port-local time. | `10` minutes after the board opens, the port clock reads 01:30 BST. The 01:30 GMT sailing is still an hour away. The base shows it and the candidate drops it from the board. | [Patch](departure-board/candidate.patch) · [Report](results/departure-board/report.json) · [New test](results/departure-board/regression.py) |

All 13 existing tests passed on both sides. They covered webhook redelivery, full batch acknowledgement and ordinary departure dates. Each exported test passed on its base commit and was the sole failure on its candidate. [Recorded test results](results/verification.json)

Each probe defines the workflow and records what a caller can observe. Undertow supplies the input, runs both revisions and repeats the distinguishing case on its own. We selected the base outcome as the contract for each generated test. [Refund probe](refunds/probe.py) · [Export probe](invoice-export/probe.py) · [Departure probe](departure-board/probe.py)
