# Contributing

Keep all committed documentation, comments, user-facing messages, examples, filenames, and commit messages in English. Use synthetic data and clearly labeled placeholders in examples. Never commit credentials, private keys, local environment files, private customer data, generated review artifacts, or personal workstation paths. Run `python3 scripts/public-check.py` and the secret scans in [Security and data handling](SECURITY.md) before publishing. Changes to `.gitignore` do not remove files or sensitive content already present in Git history.

Use JDK 25 and Maven 3.9+ for the Spring Boot 4.1.1 harness. The separate order/payment demo uses JDK 21 and Spring Boot 3.4.4. JavaParser currently analyzes Java 21 source; the harness runtime does not establish support for newer source syntax. See the [README tech stack](README.md#tech-stack). Preserve CODING-SKILL.md as the policy source. Link rule changes to its numbered sections and distinguish MUST/SHOULD/optional guidance from business impact.

Run `mvn spotless:apply verify` for the harness and `mvn -f demo/order-service/pom.xml verify` for the demo with JDK 21 (requires Docker). Test observable behavior and trust boundaries. Add paired labeled fixtures for new capabilities; keep tuning examples separate from held-out cases. Never report authored replay scores as model detection accuracy.

Run `python3 -m unittest discover -s scripts/tests -v` for the workflow helpers. `mvn verify` checks Java formatting and project-file whitespace before compilation and runs SpotBugs at the High threshold. Use `.editorconfig` in your editor; Markdown trailing spaces may be intentional line breaks. Keep demo sources and labeled fixture contents out of harness-wide formatting changes.

Use four spaces per Java block indentation level, matching Spotless's google-java-format AOSP style and `.editorconfig`. `JAVA-FORMAT-001` links this convention to CODING-SKILL.md section 33 and makes introduced violations available to the reviewer as LOW-risk STYLE findings with a MINOR guideline label.

Follow CODING-SKILL.md sections 3–5, 8, 10, 30–34, 41 and 45 for maintenance changes: use focused methods, descriptive names, explicit imports, braces and named limits. Copy mutable JSON when passing ownership to records or adapters. Propagate cancellation separately from provider/tool failures. Keep broad exception catches at deliberate integration boundaries and sanitize their diagnostics. New static-analysis suppressions must identify the exact class/bug and explain why the warning is safe; never suppress mutable-state exposure across the project.

Use explicit branches or a named method for nested conditional expressions, resource creation and multi-step decisions (section 3). Keep simple two-value expressions when both choices are immediately clear. Passing Spotless does not establish readability; review the decisions and responsibilities separately.

Do not add shell, arbitrary HTTP, source writes, test execution or publication to the model tool registry. Workflow/publisher changes need tests for stale commits, malicious artifact contents and duplicate runs. Pin dependencies/actions and verify their official release documentation.

A contribution should describe the trigger, resulting behavior, evidence, validation and any coverage limitation. Do not add generated test success labels without execution evidence.
