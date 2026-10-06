package dev.undertow.harness;

import com.fasterxml.jackson.databind.JsonNode;
import dev.undertow.findings.Finding;
import dev.undertow.findings.ReviewValidator;
import dev.undertow.model.GeminiClient;
import dev.undertow.model.ModelClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.reporting.Markdown;
import dev.undertow.reporting.Schemas;
import dev.undertow.tools.CiEvidence;
import dev.undertow.tools.GitRepository;
import dev.undertow.tools.ToolRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Reviewer {
    public static final String PROMPT_VERSION = "undertow-review-v2";
    private static final int OUTPUT_TOKEN_RESERVE = 4096;
    private final GitRepository git;
    private final Policy policy;
    private final EvidenceStore evidence = new EvidenceStore();
    private final ToolRegistry tools;

    public Reviewer(GitRepository git, Path ciBundle) throws IOException {
        this(git, ciBundle, null);
    }

    public Reviewer(GitRepository git, Path ciBundle, Path ciReceipt) throws IOException {
        this(git, new Policy(git), ciBundle, ciReceipt);
    }

    public Reviewer(GitRepository git, Policy effectivePolicy, Path ciBundle, Path ciReceipt)
            throws IOException {
        this.git = git;
        policy = effectivePolicy;
        tools = new ToolRegistry(git, policy, evidence, CiEvidence.load(ciBundle, git, ciReceipt));
    }

    public Policy policy() {
        return policy;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public Report run(ModelClient model, Path output, String mode)
            throws IOException, InterruptedException {
        if (!Set.of("tools", "diff", "collect").contains(mode)) {
            throw new IllegalArgumentException("Unknown review mode");
        }
        Files.createDirectories(output);
        var run = new ReviewRun(model, mode);
        // Cancel outstanding tasks without waiting for an uncooperative provider to finish.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("trace.jsonl"))) {
            run.collectBaseline();
            run.investigate(executor, writer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            executor.shutdownNow();
        }
        Report report = run.report();
        Schemas.validate(Schemas.resource("report"), Json.MAPPER.valueToTree(report));
        Json.write(output.resolve("evidence.json"), evidence.entries());
        Json.write(output.resolve("report.json"), report);
        Json.write(
                output.resolve("publication.json"),
                new dev.undertow.changes.ChangeAdapters.Publication(
                        "not_requested",
                        0,
                        report.headSha(),
                        report.runId(),
                        Map.of(),
                        "Publication is a separate authorized step"));
        Files.writeString(output.resolve("report.md"), Markdown.render(report));
        return report;
    }

    /** Mutable accounting is confined to one run; tool calls remain sequential. */
    private final class ReviewRun {
        private final ModelClient model;
        private final String mode;
        private final Policy.Limits limits = policy.limits();
        private final long started = System.nanoTime();
        private final long deadline =
                started + TimeUnit.SECONDS.toNanos(limits.runTimeoutSeconds());
        private final String runId = UUID.randomUUID().toString();
        private final List<String> failures = new ArrayList<>();
        private final List<String> extraGaps = new ArrayList<>();
        private String instruction = prompt();
        private long contextEstimate = estimate(instruction);
        private long inputTokens;
        private long outputTokens;
        private long estimatedInputTokens;
        private int modelCalls;
        private int toolCalls;
        private int repairs;
        private boolean limited;
        private boolean invalid;
        private String failureCause = "none";
        private List<ModelClient.Observation> observations = List.of();
        private ReviewValidator.Review review;

        private ReviewRun(ModelClient model, String mode) throws IOException {
            this.model = model;
            this.mode = mode;
        }

        private void collectBaseline() throws IOException, InterruptedException {
            if (mode.equals("tools")) {
                return;
            }
            JsonNode diff = tools.execute("get_diff", Json.MAPPER.createObjectNode());
            toolCalls++;
            if (mode.equals("diff")) {
                instruction += "\nUntrusted diff evidence:\n" + Json.stringify(diff);
                instruction +=
                        "\nLocation evidence IDs (content withheld for this diff-only baseline): "
                                + Json.stringify(collectLocationReferences());
            }
            contextEstimate = estimate(instruction);
        }

        private JsonNode collectLocationReferences() throws IOException, InterruptedException {
            var references = Json.MAPPER.createArrayNode();
            List<String> headFiles = git.files("head");
            for (String path : tools.changed()) {
                if (!ToolRegistry.supported(path)) {
                    continue;
                }
                String snapshot = headFiles.contains(path) ? "head" : "base";
                int lines = Math.min(200, git.source(snapshot, path).split("\n", -1).length);
                var arguments =
                        Json.MAPPER
                                .createObjectNode()
                                .put("snapshot", snapshot)
                                .put("path", path)
                                .put("start_line", 1)
                                .put("end_line", lines);
                JsonNode source = tools.execute("read_source", arguments);
                toolCalls++;
                references
                        .addObject()
                        .put("path", path)
                        .put("evidence_id", source.path("evidence_id").asText());
            }
            return references;
        }

        private void investigate(ExecutorService executor, BufferedWriter writer)
                throws IOException, InterruptedException {
            if (tools.changed().isEmpty()) {
                review =
                        new ReviewValidator.Review(
                                List.of(), "No changed files; review skipped.", List.of());
                return;
            }
            if (mode.equals("collect")) {
                review =
                        new ReviewValidator.Review(
                                List.of(),
                                "Deterministic collection completed; no semantic risk review was performed.",
                                List.of("Semantic review skipped in collector mode"));
                return;
            }
            while (modelCalls < limits.maxModelCalls()) {
                boolean finalOnly =
                        mode.equals("diff")
                                || toolCalls >= limits.maxToolCalls()
                                || modelCalls == limits.maxModelCalls() - 1
                                || repairs > 0;
                ModelClient.Turn turn = requestTurn(executor, writer, finalOnly);
                if (turn == null || !recordUsage(turn, writer)) {
                    break;
                }
                List<ModelClient.Observation> next =
                        executeCalls(turn.calls(), finalOnly, executor, writer);
                if (turn.review() != null) {
                    if (acceptReview(turn, next)) {
                        break;
                    }
                } else if (turn.calls().isEmpty()) {
                    failures.add("Model produced no action");
                    failureCause = "invalid_output";
                    limited = true;
                    break;
                }
                observations = List.copyOf(next);
            }
            if (review == null && !invalid) {
                limited = true;
            }
        }

        private boolean canRequestTurn() {
            long accountedTokens =
                    Math.max(inputTokens + outputTokens, estimatedInputTokens + outputTokens);
            return modelCalls < limits.maxModelCalls()
                    && System.nanoTime() < deadline
                    && contextEstimate + OUTPUT_TOKEN_RESERVE <= limits.maxContextTokens()
                    && accountedTokens + contextEstimate + OUTPUT_TOKEN_RESERVE
                            <= limits.maxTotalTokens();
        }

        private ModelClient.Turn requestTurn(
                ExecutorService executor, BufferedWriter writer, boolean finalOnly)
                throws IOException, InterruptedException {
            String modelInstruction = nextInstruction();
            for (int retry = 0; retry <= limits.retryAttempts(); retry++) {
                if (!canRequestTurn()) {
                    limited = true;
                    return null;
                }
                modelCalls++;
                estimatedInputTokens += contextEstimate;
                long callStarted = System.nanoTime();
                List<ModelClient.Observation> pendingObservations = observations;
                try {
                    ModelClient.Turn turn =
                            bounded(
                                    executor,
                                    () ->
                                            model.next(
                                                    modelInstruction,
                                                    pendingObservations,
                                                    finalOnly),
                                    deadline,
                                    limits.toolTimeoutSeconds());
                    event(writer, runId, "model", model.identity(), callStarted, "ok", List.of());
                    return turn;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                } catch (Exception e) {
                    // The model boundary isolates provider failures and omits sensitive error
                    // payloads.
                    event(
                            writer,
                            runId,
                            "model",
                            model.identity(),
                            callStarted,
                            "failed",
                            List.of());
                    if (e instanceof GeminiClient.ModelFailure failure
                            && failure.transientFailure()
                            && retry < limits.retryAttempts()) {
                        long delay = failure.retryDelayMillis(retry);
                        if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay) >= deadline) {
                            failureCause = "budget";
                            failures.add("Run budget cannot accommodate provider retry delay");
                            limited = true;
                            return null;
                        }
                        Thread.sleep(delay);
                        continue;
                    }
                    failures.add(
                            "Model request failed: "
                                    + (e instanceof GeminiClient.ModelFailure
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName()));
                    failureCause =
                            e instanceof GeminiClient.ModelFailure failure
                                    ? failure.category()
                                    : e instanceof TimeoutException
                                                    || e
                                                            instanceof
                                                            java.net.http.HttpTimeoutException
                                            ? "timeout"
                                            : e instanceof IOException
                                                    ? "invalid_output"
                                                    : "unavailable";
                    limited = true;
                    return null;
                }
            }
            return null;
        }

        private String nextInstruction() {
            if (modelCalls == 0) {
                return instruction;
            }
            if (repairs > 0) {
                return "Previous review was rejected. Repair the validation errors from the tool response. Submit finish_review only.";
            }
            return "";
        }

        private boolean recordUsage(ModelClient.Turn turn, BufferedWriter writer)
                throws IOException {
            if (turn.inputTokens() < 0 || turn.outputTokens() < 0) {
                invalid = true;
                failureCause = "invalid_output";
                failures.add("Invalid token accounting");
                return false;
            }
            writer.write(
                    Json.stringify(
                            Map.of(
                                    "run_id",
                                    runId,
                                    "stage",
                                    "usage",
                                    "input_tokens",
                                    turn.inputTokens(),
                                    "output_tokens",
                                    turn.outputTokens())));
            writer.newLine();
            writer.flush();
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            if (inputTokens + outputTokens > limits.maxTotalTokens()) {
                limited = true;
                extraGaps.add(
                        "Provider-reported token usage exceeded the application estimate; billing caps are not guaranteed");
            }
            return true;
        }

        private List<ModelClient.Observation> executeCalls(
                List<ModelClient.Call> calls,
                boolean finalOnly,
                ExecutorService executor,
                BufferedWriter writer)
                throws IOException, InterruptedException {
            List<ModelClient.Observation> results = new ArrayList<>();
            for (ModelClient.Call call : calls) {
                JsonNode result = executeCall(call, finalOnly, executor, writer);
                contextEstimate += estimate(Json.stringify(result)) + 100;
                results.add(new ModelClient.Observation(call.id(), call.name(), result));
            }
            return results;
        }

        private JsonNode executeCall(
                ModelClient.Call call,
                boolean finalOnly,
                ExecutorService executor,
                BufferedWriter writer)
                throws IOException, InterruptedException {
            if (finalOnly || toolCalls >= limits.maxToolCalls()) {
                limited = true;
                return Json.MAPPER
                        .createObjectNode()
                        .put("error", "Investigation budget exhausted; submit final review");
            }
            toolCalls++;
            long callStarted = System.nanoTime();
            try {
                JsonNode result =
                        bounded(
                                executor,
                                () -> tools.execute(call.name(), call.arguments()),
                                deadline,
                                limits.toolTimeoutSeconds());
                event(
                        writer,
                        runId,
                        "tool",
                        call.name(),
                        callStarted,
                        "ok",
                        List.of(result.path("evidence_id").asText()));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                // A rejected tool remains an observation so the model can report incomplete
                // coverage.
                failures.add(
                        "Tool "
                                + safeName(call.name())
                                + " failed: "
                                + e.getClass().getSimpleName());
                event(writer, runId, "tool", call.name(), callStarted, "failed", List.of());
                return Json.MAPPER
                        .createObjectNode()
                        .put(
                                "error",
                                "Tool rejected or unavailable; check declared arguments and report coverage gap");
            }
        }

        private boolean acceptReview(ModelClient.Turn turn, List<ModelClient.Observation> next)
                throws IOException {
            contextEstimate += estimate(Json.stringify(turn.review()));
            try {
                review = ReviewValidator.validate(turn.review(), git, policy, evidence);
                return true;
            } catch (IllegalArgumentException | IOException e) {
                if (repairs++ == 0) {
                    next.add(
                            new ModelClient.Observation(
                                    turn.reviewCallId(),
                                    "finish_review",
                                    Json.MAPPER.createObjectNode().put("error", e.getMessage())));
                    return false;
                }
                invalid = true;
                failureCause = "invalid_output";
                failures.add("Final review failed validation after one repair");
                return true;
            }
        }

        private List<String> coverageGaps() {
            List<String> gaps = new ArrayList<>(tools.gaps());
            gaps.addAll(extraGaps);
            if (!tools.ciEvidence().path("verified").asBoolean())
                gaps.add(
                        "CI execution/analyzer evidence unavailable or unverified; proposed tests were not executed");
            if (review != null) {
                gaps.addAll(review.coverageNotes());
            }
            for (var row : ruleCoverage())
                if (row.status().equals("not_evaluated"))
                    gaps.add("Rule " + row.id() + " not evaluated: " + row.reason());
            if (limited) {
                gaps.add(
                        review == null
                                ? "Reviewer stopped before a validated final review: quota, timeout or budget limit"
                                : "Investigation was limited by the application budget");
            }
            if (tools.changed().contains("pom.xml")
                    && evidence.entries().stream()
                            .noneMatch(entry -> entry.kind().equals("compare_dependencies"))) {
                gaps.add("Changed dependencies were not investigated");
            }
            return List.copyOf(new LinkedHashSet<>(gaps));
        }

        private List<dev.undertow.policy.RuleCatalog.Coverage> ruleCoverage() {
            List<dev.undertow.policy.RuleCatalog.Coverage> rows = new ArrayList<>();
            for (var row : policy.catalog().selection(tools.changed())) {
                if (!row.status().equals("selected")) {
                    rows.add(row);
                    continue;
                }
                String result = "not_evaluated";
                String reason =
                        "No explicit evidence-backed assessment; source reads alone do not prove evaluation";
                var rule = policy.rule(row.id());
                if (rule.path("execution").asText().equals("deterministic")) {
                    String kind =
                            rule.path("executor").asText().equals("junit") ? "tests" : "spotbugs";
                    if (tools.ciEvidence().path("verified").asBoolean()
                            && tools.ciEvidence().path("head").has(kind)) {
                        result = "evaluated";
                        reason =
                                "Registered "
                                        + rule.path("executor").asText()
                                        + " analyzer imported from verified CI; see CI evidence";
                    } else reason = "Verified head CI evidence required for " + kind;
                }
                if (review != null && !mode.equals("collect")) {
                    for (var assessment : review.ruleAssessments()) {
                        if (assessment.id().equals(row.id())
                                && rule.path("execution").asText().equals("contextual")) {
                            result = assessment.status();
                            reason = assessment.reason();
                        }
                    }
                }
                rows.add(
                        new dev.undertow.policy.RuleCatalog.Coverage(
                                row.id(),
                                row.version(),
                                result,
                                reason,
                                row.paths(),
                                row.exceptions()));
            }
            return List.copyOf(rows);
        }

        private String status(List<String> gaps) {
            if (invalid) {
                return "failed";
            }
            if (tools.changed().isEmpty()) {
                return "skipped";
            }
            return limited || !gaps.isEmpty() || !failures.isEmpty() ? "partial" : "complete";
        }

        private Report report() throws IOException {
            List<String> gaps = coverageGaps();
            String summary;
            List<Finding> findings;
            if (review == null) {
                summary = "No validated review available; inspect coverage and tool failures.";
                findings = List.of();
            } else {
                summary = review.summary();
                findings = review.findings();
            }
            return new Report(
                    2,
                    runId,
                    status(gaps),
                    git.base(),
                    git.head(),
                    model.identity(),
                    PROMPT_VERSION,
                    Json.hash(instruction),
                    policy.hashes(),
                    summary,
                    findings,
                    gaps,
                    tools.changed(),
                    tools.inspected().stream().sorted().toList(),
                    failures,
                    modelCalls,
                    toolCalls,
                    inputTokens,
                    outputTokens,
                    estimatedInputTokens,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    Json.hash(Json.stringify(evidence.entries())),
                    new ReviewDetails(
                            mode.equals("collect")
                                    ? "collect"
                                    : model instanceof dev.undertow.model.ReplayClient
                                            ? "replay"
                                            : "live",
                            failureCause.equals("none") && limited ? "budget" : failureCause,
                            Runtime.version().toString(),
                            "javaparser-3.26.3/java21-syntax",
                            git.policyCommit(),
                            git.targetHead(),
                            ruleCoverage(),
                            tools.ciEvidence(),
                            Map.of(
                                    "undertow",
                                    "0.1.0-alpha.1",
                                    "spring_boot",
                                    "4.1.1",
                                    "google_genai",
                                    "1.65.0",
                                    "javaparser",
                                    "3.26.3")));
        }
    }

    private String prompt() throws IOException {
        return "Review Java 21 single-module Maven changes at base="
                + git.base()
                + " head="
                + git.head()
                + ". "
                + "Investigate changed behavior and relevant callers/tests/configuration. Collect get_diff and source ranges before findings. "
                + "Every finding must reference diff evidence and source evidence covering its exact pinned location. "
                + "Only report regressions introduced/activated/worsened by this change; explain the causal change and trigger. "
                + "Risk LOW/MEDIUM/HIGH/CRITICAL/HIGH_CRITICAL is distinct from category and guideline label. "
                + "Critical requires high confidence and technical evidence. High Critical also requires owner-declared catastrophic scope. "
                + "Unverified high-impact hypotheses are investigation items, not verified critical findings. "
                + "Honor legacy exceptions, domain pragmatism, safe records/virtual threads. Generated tests are proposed only. "
                + "Dependency release claims need retrieved official version-context evidence; advisory severity is not exploitation. "
                + "Missing/truncated/unresolved evidence must be coverage_notes. Stop within budgets. No paid fallback. "
                + "Return rule_assessments for each selected contextual rule: evaluated requires required tool evidence_refs, with source evidence for every path in declared scope; otherwise not_evaluated with reason. "
                + "Never claim that reading a file evaluates all rules. Deterministic rules are assessed by registered CI analyzers only. "
                + "Changed files: "
                + Json.stringify(tools.changed())
                + "\nTrusted rules: "
                + Json.stringify(promptRules())
                + "\nTrusted applicability and exceptions: "
                + Json.stringify(policy.catalog().selection(tools.changed()))
                + "\nSubmit the declared finish_review schema; tests must have status proposed or not executable here.";
    }

    private JsonNode promptRules() {
        var selected = Json.MAPPER.createArrayNode();
        for (JsonNode rule : policy.rules()) {
            var row = selected.addObject();
            for (String key :
                    List.of(
                            "id",
                            "wording",
                            "strength",
                            "label",
                            "execution",
                            "required_tools",
                            "executor")) if (rule.has(key)) row.set(key, rule.get(key));
        }
        return selected;
    }

    private static long estimate(String text) {
        return (text.length() + 2L) / 3L;
    }

    private static <T> T bounded(
            ExecutorService executor, Callable<T> task, long deadline, int seconds)
            throws Exception {
        long remaining = Math.min(TimeUnit.SECONDS.toNanos(seconds), deadline - System.nanoTime());
        if (remaining <= 0) {
            throw new TimeoutException("Run deadline reached");
        }
        Future<T> future = executor.submit(task);
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        } finally {
            future.cancel(true);
        }
    }

    private static String safeName(String value) {
        return value.matches("[A-Za-z0-9_.-]{1,100}") ? value : "invalid";
    }

    private static void event(
            BufferedWriter writer,
            String run,
            String stage,
            String name,
            long started,
            String outcome,
            List<String> ids)
            throws IOException {
        writer.write(
                Json.stringify(
                        Map.of(
                                "run_id",
                                run,
                                "stage",
                                stage,
                                "name",
                                safeName(name),
                                "duration_ms",
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                                "outcome",
                                outcome,
                                "evidence_ids",
                                ids)));
        writer.newLine();
        writer.flush();
    }
}
