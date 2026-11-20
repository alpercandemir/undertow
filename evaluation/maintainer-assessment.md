# Independent maintainer assessment

- Reviewer ID or pseudonym
- Repository and role
- Assessment date
- Packet SHA-256 supplied by coordinator
- Cases assessed
- Prior familiarity with any selected case

Use the pinned base and head in `reviewer.json`. Open the source directly at those revisions. The coordinator can provide a two-tree diff if that is easier. Assess independently and record uncertainty. Keep the original PR conversation, later fixes, other assessments, and the tool key closed until you submit. If you recall the original change, record that as prior knowledge.

## Pass 1 · inspect the change before opening its findings

Repeat for each assigned case, even if its finding list is empty. Use the anonymous case ID. Record behavior you can establish from base/head source and tests. Mark suspected changes that need verification.

| Case ID | Material behavior or none found | Distinguishing input | Base outcome | Head outcome | Source or test evidence | Impact on clients or workflows |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

## Pass 2 · assess each finding independently

Correctness means the claimed externally observable behavior follows from these revisions. For a concrete example, verify the same input and both outcomes. A missing example and a wrong example should receive different responses. Business relevance includes API consumers, policy intent, compatibility, operational effects, and user workflows. An intentional behavior change can be correct and important.

| Finding ID | Correctness (correct / incorrect / uncertain) | Concrete example (accurate / inaccurate / missing / uncertain) | Business relevance (material / minor / none / uncertain) | Already known before this assessment (yes / no / unsure) | Would change a test, fix, policy confirmation or release decision? Give the specific change, or record none/unsure | Evidence and reasoning |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

Compare your Pass 1 observations with every finding in this case. Record important behavior the packet missed, partial coverage, or a finding with the wrong scope. Include source or test evidence and the decision the missed behavior would affect. An empty findings list can also reflect a provider run that was unavailable. The coordinator will reconcile misses with run coverage after unblinding.

## Follow-through · complete separately if an action actually occurs

Record the anonymous finding ID, who took the action, what changed, the date, and evidence such as a test diff, fix, explicit policy confirmation, or release decision. For a historical case, record a new follow-through linked to this finding separately from an action taken during the original PR.

| Finding ID | Action taken and by whom | Date | Evidence | Attribution to this finding |
| --- | --- | --- | --- | --- |
| | | | | |

## Coordinator

Give each of at least two independent maintainers their own copy of this template and the reviewer packet. For Pass 1, provide the case IDs and revisions with the `findings` keys removed. Reveal the anonymous findings for Pass 2. Use the same sampled cases where feasible and ensure the regression, intentional changes, and refactor receive coverage. Keep the labeled outcomes, source-of-finding key, original outputs and provisional scores with the coordinator. Reconcile disagreements against pinned source or tests after both reviews are in. Preserve the individual assessments and reasons for any resolution. Keep uncertain verdicts unverified and observed actions separate from hypothetical impact and historical actions.
