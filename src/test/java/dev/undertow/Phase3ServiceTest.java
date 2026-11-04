package dev.undertow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.harness.Reviewer;
import dev.undertow.model.ModelClient;
import dev.undertow.model.ReplayClient;
import dev.undertow.policy.Policy;
import dev.undertow.reporting.Json;
import dev.undertow.service.HostedWorker;
import dev.undertow.service.ServiceControl;
import dev.undertow.service.ServiceDatabase;
import dev.undertow.service.ServiceException;
import dev.undertow.service.ServiceGateway;
import dev.undertow.service.ServiceHttpServer;
import dev.undertow.service.ServiceOrchestrator;
import dev.undertow.service.ServicePolicy;
import dev.undertow.service.ServiceSecurity;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Phase3ServiceTest {
    private static final long RESERVATION = 80000;
    @TempDir Path temp;
    private ServiceDatabase database;
    private ServiceControl control;
    private FakeGithub github;
    private MutableClock clock;
    private ServiceControl.Settings settings;
    private ServiceControl.Session alice;
    private ServiceControl.Session bob;
    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setup() throws Exception {
        clock = new MutableClock();
        github = new FakeGithub(new TestRepository(temp.resolve("source")));
        database =
                new ServiceDatabase(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", new byte[32]);
        JsonNode execution = Json.YAML.readTree(Files.readString(Path.of("config/review.yaml")));
        settings =
                new ServiceControl.Settings(
                        "sha256:" + "a".repeat(64),
                        "internal-execution-v1",
                        "authored-test-tariff",
                        1,
                        1,
                        RESERVATION,
                        2,
                        1,
                        true,
                        execution,
                        Json.YAML.readTree("sources: {}\nallowed_hosts: []\n"));
        control = new ServiceControl(database, github, settings, clock);
        alice = login("1");
        bob = login("2");
        tenantA = control.connect(alice, 101).path("tenant").asText();
        tenantB = control.connect(bob, 202).path("tenant").asText();
        control.registerRepository(alice, tenantA, 11);
        control.registerRepository(bob, tenantB, 22);
        enable(alice, tenantA, "11");
        enable(bob, tenantB, "22");
        control.grant(
                tenantA,
                RESERVATION * 10,
                clock.millis() + Duration.ofDays(30).toMillis(),
                "grant-alice",
                "operator",
                "authored test grant");
        control.grant(
                tenantB,
                RESERVATION * 10,
                clock.millis() + Duration.ofDays(30).toMillis(),
                "grant-bob",
                "operator",
                "authored test grant");
    }

    private static ObjectNode node() {
        return Json.MAPPER.createObjectNode();
    }

    private ServiceControl.Session login(String user) throws Exception {
        String state = control.beginLogin();
        ObjectNode response = control.finishLogin(state, state, user);
        return control.session(
                response.path("session").asText(), response.path("csrf").asText(), true);
    }

    private void enable(ServiceControl.Session user, String tenant, String repo) throws Exception {
        control.configure(user, tenant, repo, node().put("enabled", true).put("consent", true));
    }

    private ObjectNode request(int number, String key) throws Exception {
        return control.requestReview(alice, tenantA, "11", number, key);
    }

    private ObjectNode usage(boolean known, long input, long output) {
        return node().put("known", known).put("inputTokens", input).put("outputTokens", output);
    }

    private ObjectNode syntheticArtifact() {
        ObjectNode value = node();
        value.putObject("files").putObject("report.json").put("status", "partial");
        return value;
    }

    private long available() throws Exception {
        return control.credits(alice, tenantA).path("available").asLong();
    }

    @Test
    void oauthStateIsBoundedSingleUseAndCsrfIsRequired() throws Exception {
        String state = control.beginLogin();
        assertThatThrownBy(() -> control.finishLogin(state, "other", "1"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("invalid_oauth_state");
        ObjectNode signedIn = control.finishLogin(state, state, "1");
        assertThatThrownBy(() -> control.finishLogin(state, state, "1"))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> control.session(signedIn.path("session").asText(), "wrong", true))
                .hasMessage("invalid_csrf");
        control.logout(signedIn.path("session").asText());
        assertThatThrownBy(() -> control.session(signedIn.path("session").asText(), null, false))
                .hasMessage("session_expired");
        String expired = control.beginLogin();
        clock.advance(Duration.ofMinutes(11));
        assertThatThrownBy(() -> control.finishLogin(expired, expired, "1"))
                .hasMessage("expired_oauth_state");
    }

    @Test
    void installationIdsAreNotAuthorityAndAccountsCannotBeReassigned() throws Exception {
        assertThatThrownBy(() -> control.connect(bob, 101))
                .hasMessage("installation_authority_denied");
        github.installationOwners.put(303L, "1");
        github.installationAccounts.put(303L, 101L);
        assertThatThrownBy(() -> control.connect(alice, 303))
                .hasMessage("account_already_connected");
        assertThatThrownBy(() -> control.registerRepository(alice, tenantA, 22))
                .hasMessage("github_access_denied");
    }

    @Test
    void crossTenantIdsAndBillingOnlyUsersCannotReadSourceOrSpend() throws Exception {
        ObjectNode job = request(1, "manual-0001");
        assertThatThrownBy(() -> control.review(bob, tenantB, job.path("id").asText()))
                .hasMessage("not_found");
        assertThatThrownBy(() -> control.review(bob, tenantA, job.path("id").asText()))
                .hasMessage("tenant_access_denied");
        var billing = login("3");
        control.membership(
                alice, tenantA, "3", Json.MAPPER.createArrayNode().add("billing_viewer"));
        assertThat(control.credits(billing, tenantA).path("available").asLong())
                .isEqualTo(RESERVATION * 9);
        assertThatThrownBy(() -> control.review(billing, tenantA, job.path("id").asText()))
                .hasMessage("role_denied");
        assertThatThrownBy(() -> control.requestReview(billing, tenantA, "11", 2, "manual-0002"))
                .hasMessage("role_denied");
        assertThat(Json.stringify(control.usage(billing, tenantA)))
                .doesNotContain("snapshot", "src/", "effectivePolicy", "token");
    }

    @Test
    void multiTenantMembershipStillIntersectsLiveRepositoryAccess() throws Exception {
        var dual = login("3");
        control.membership(alice, tenantA, "3", Json.MAPPER.createArrayNode().add("reviewer"));
        control.membership(bob, tenantB, "3", Json.MAPPER.createArrayNode().add("reviewer"));
        assertThat(control.tenants(dual)).hasSize(2);
        ObjectNode a = request(1, "manual-0001");
        ObjectNode b = control.requestReview(bob, tenantB, "22", 1, "manual-0001");
        assertThat(control.review(dual, tenantA, a.path("id").asText()).path("id"))
                .isEqualTo(a.path("id"));
        assertThat(control.review(dual, tenantB, b.path("id").asText()).path("id"))
                .isEqualTo(b.path("id"));
        github.access.put("fake-token-3", Set.of(22L));
        assertThatThrownBy(() -> control.review(dual, tenantA, a.path("id").asText()))
                .hasMessage("github_access_denied");
    }

    @Test
    void parallelReservationsCannotOverspendAndApiRetriesReuseTheReview() throws Exception {
        ServiceControl small = new ServiceControl(database, github, settings, clock);
        control.operatorControl(tenantA, "operator", "suspend", "test suspension");
        control.operatorControl(tenantA, "operator", "resume", "test resume");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<ObjectNode>>();
            for (int i = 1; i <= 20; i++) {
                final int number = i;
                tasks.add(
                        executor.submit(
                                () ->
                                        small.requestReview(
                                                alice,
                                                tenantA,
                                                "11",
                                                number,
                                                "parallel-" + number)));
            }
            int admitted = 0;
            for (var task : tasks)
                if (task.get().path("admission").asText().equals("accepted")) admitted++;
            assertThat(admitted).isEqualTo(10);
        }
        assertThat(available()).isZero();
        ObjectNode first = request(1, "parallel-1");
        assertThat(request(1, "parallel-1").path("id")).isEqualTo(first.path("id"));
        assertThatThrownBy(() -> request(99, "parallel-1")).hasMessage("idempotency_conflict");
        assertThat(control.credits(alice, tenantA).path("ledger"))
                .filteredOn(e -> e.path("type").asText().equals("reserve"))
                .hasSize(10);
    }

    @Test
    void manualRerunUsesNewIdentityAndReservationsAndNoConsentBlocksOptIn() throws Exception {
        ObjectNode first = request(1, "manual-0001"), second = request(1, "manual-0002");
        assertThat(second.path("id")).isNotEqualTo(first.path("id"));
        assertThat(control.review(alice, tenantA, first.path("id").asText()).path("job").asText())
                .isEqualTo("cancelled");
        assertThat(available()).isEqualTo(RESERVATION * 9);
        control.configure(alice, tenantA, "11", node().put("enabled", false));
        assertThatThrownBy(
                        () -> control.configure(alice, tenantA, "11", node().put("enabled", true)))
                .hasMessage("consent_required");
        ObjectNode blocked = request(2, "manual-0003");
        assertThat(blocked.path("reviewOutcome").asText()).isEqualTo("not_run");
        assertThat(blocked.path("reason").asText()).isEqualTo("repository_disabled");
    }

    @Test
    void oneHundredDuplicateDeliveriesCreateOneReviewAndReservation() throws Exception {
        ObjectNode body = event("opened", 1);
        for (int i = 0; i < 100; i++) control.receive("delivery-" + i, "pull_request", body);
        control.processEvents();
        control.processEvents();
        assertThat(control.history(alice, tenantA, "11")).hasSize(1);
        assertThat(available()).isEqualTo(RESERVATION * 9);
        assertThatThrownBy(() -> control.receive("delivery-0", "pull_request", event("closed", 1)))
                .hasMessage("delivery_conflict");
    }

    @Test
    void forkAndDraftEventsNeverSpendAndReorderedEventsResolveLiveState() throws Exception {
        github.internal = false;
        control.receive("fork-event", "pull_request", event("opened", 1));
        control.processEvents();
        assertThat(control.history(alice, tenantA, "11")).isEmpty();
        assertThat(available()).isEqualTo(RESERVATION * 10);
        github.internal = true;
        github.draft = true;
        control.receive("draft-event", "pull_request", event("synchronize", 1));
        control.processEvents();
        assertThat(control.history(alice, tenantA, "11").getFirst().path("reviewOutcome").asText())
                .isEqualTo("not_run");
        assertThat(available()).isEqualTo(RESERVATION * 10);
    }

    @Test
    void grantExpiryAndReleaseCannotResurrectCredits() throws Exception {
        clock.advance(Duration.ofDays(31));
        assertThat(available()).isZero();
        control.grant(
                tenantA,
                RESERVATION,
                clock.millis() + 1000,
                "short-grant",
                "operator",
                "short lived grant");
        request(1, "manual-0001");
        ObjectNode lease = control.lease("one");
        clock.advance(Duration.ofSeconds(2));
        assertThat(available()).isZero();
        control.fail(lease, "preparation_failed");
        assertThat(available()).isZero();
        ObjectNode blocked = request(2, "manual-0002");
        assertThat(blocked.path("reason").asText()).isEqualTo("insufficient_credits");
    }

    @Test
    void unknownUsageAndPostInferenceCrashesHoldThenAbsorbWithin24Hours() throws Exception {
        request(1, "manual-0001");
        ObjectNode lease = control.lease("one");
        control.startInference(lease, "live");
        clock.advance(Duration.ofMinutes(11));
        control.maintenance();
        ObjectNode review = control.review(alice, tenantA, lease.path("id").asText());
        assertThat(review.path("metering").asText()).isEqualTo("reconciliation_required");
        assertThat(control.lease("two")).isNull();
        assertThat(available()).isEqualTo(RESERVATION * 9);
        clock.advance(Duration.ofHours(24));
        control.maintenance();
        assertThat(available()).isEqualTo(RESERVATION * 10);
        assertThat(control.credits(alice, tenantA).path("ledger"))
                .anySatisfy(
                        e ->
                                assertThat(e.path("reason").asText())
                                        .isEqualTo("service_absorbed_reconciliation_timeout"));
    }

    @Test
    void preInferenceCrashRetriesAreBoundedAndOldFencesCannotFinish() throws Exception {
        request(1, "manual-0001");
        ObjectNode first = control.lease("one");
        clock.advance(Duration.ofMinutes(11));
        ObjectNode second = control.lease("two");
        assertThat(second.path("fence").asLong()).isGreaterThan(first.path("fence").asLong());
        assertThatThrownBy(() -> control.startInference(first, "replay")).hasMessage("lease_lost");
        clock.advance(Duration.ofMinutes(11));
        assertThat(control.lease("three")).isNotNull();
        clock.advance(Duration.ofMinutes(11));
        assertThat(control.lease("four")).isNull();
        assertThat(available()).isEqualTo(RESERVATION * 10);
    }

    @Test
    void partialLiveUsageSettlesOnceAndPublicationRecoveryDoesNotChargeInference()
            throws Exception {
        request(1, "manual-0001");
        ObjectNode lease = control.lease("one");
        control.startInference(lease, "live");
        control.complete(lease, syntheticArtifact(), usage(true, 30, 20));
        assertThat(available()).isEqualTo(RESERVATION * 10 - 50);
        assertThatThrownBy(() -> control.complete(lease, syntheticArtifact(), usage(true, 30, 20)))
                .hasMessage("lease_lost");
        ObjectNode publish = control.leasePublication("publisher");
        assertThat(control.leasePublication("other-publisher")).isNull();
        control.publicationResult(publish, node().put("status", "failed"));
        clock.advance(Duration.ofMinutes(2));
        ObjectNode retry = control.leasePublication("retry-publisher");
        assertThat(retry.path("id")).isEqualTo(lease.path("id"));
        control.publicationResult(retry, node().put("status", "published"));
        assertThat(available()).isEqualTo(RESERVATION * 10 - 50);
    }

    @Test
    void immutablePolicyModesRejectCrossTenantPackagesAndIgnoreHeadConfiguration()
            throws Exception {
        ObjectNode input = node();
        input.set("files", Json.MAPPER.valueToTree(github.files));
        ObjectNode pack = control.createPackage(alice, tenantA, input);
        assertThatThrownBy(
                        () ->
                                control.configure(
                                        bob,
                                        tenantB,
                                        "22",
                                        node().put("policySource", "central")
                                                .put("policy", pack.path("id").asText())))
                .hasMessage("not_found");
        control.configure(
                alice,
                tenantA,
                "11",
                node().put("policySource", "central").put("policy", pack.path("id").asText()));
        ObjectNode review = request(1, "manual-0001");
        assertThat(review.path("policyIdentity")).isEqualTo(pack.path("id"));
        Policy resolved =
                ServicePolicy.resolve(github.files, settings.execution(), settings.sources());
        assertThat(resolved.keyEnv()).isEqualTo("GEMINI_API_KEY");
        assertThat(resolved.hashes())
                .containsKeys("@service/execution.json", "@service/dependency-sources.json")
                .doesNotContainKey("config/review.yaml");
        var bad = new HashMap<>(github.files);
        bad.put(".undertow/rules.yaml", "rules: []\nmodel: {api_key_env: UNDERTOW_DATABASE_KEY}\n");
        assertThatThrownBy(
                        () -> ServicePolicy.resolve(bad, settings.execution(), settings.sources()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revokedAccessAndKillSwitchPreventDispatchAndDeleteRemovesSource() throws Exception {
        ObjectNode review = request(1, "manual-0001"), lease = control.lease("one");
        github.access.put("fake-token-1", Set.of());
        assertThatThrownBy(() -> control.verifyExecutionAccess(lease))
                .hasMessage("github_access_denied");
        control.fail(lease, "access_revoked");
        assertThat(available()).isEqualTo(RESERVATION * 10);
        github.access.put("fake-token-1", Set.of(11L, 12L));
        control.operatorControl("", "operator", "disable_service", "provider incident");
        assertThat(request(2, "manual-0002").path("reason").asText()).isEqualTo("service_disabled");
        assertThat(control.lease("two")).isNull();
        control.operatorControl("", "operator", "enable_service", "provider restored");
        control.operatorControl(tenantA, "operator", "delete", "customer deletion");
        assertThatThrownBy(() -> control.review(alice, tenantA, review.path("id").asText()))
                .hasMessage("tenant_disabled");
        assertThat(database.<List<ObjectNode>>transaction(db -> db.list("preparation", tenantA)))
                .isEmpty();
    }

    @Test
    void installationRemovalCancelsWorkAndInvalidatesEvidenceAccess() throws Exception {
        request(1, "manual-0001");
        ObjectNode event = node().put("action", "deleted");
        event.putObject("installation").put("id", 101);
        control.receive("removed-installation", "installation", event);
        control.processEvents();
        assertThat(available()).isEqualTo(RESERVATION * 10);
        assertThatThrownBy(() -> request(2, "manual-0002")).hasMessage("repository_revoked");
    }

    @Test
    void hostedReplayAndLocalEffectivePolicyProduceEquivalentReports() throws Exception {
        ObjectNode job = request(1, "manual-0001"), lease = control.lease("one");
        ObjectNode manifest = ServiceOrchestrator.manifest(lease, control.preparation(lease));
        var replay =
                Json.parse(
                        "{\"review\":{\"summary\":\"Authored benign replay\",\"findings\":[],\"coverage_gaps\":[\"No live model evaluation\"]}}");
        ObjectNode artifact =
                HostedWorker.run(
                        manifest,
                        github.source.root,
                        temp.resolve("hosted"),
                        new ReplayClient(List.of(replay)),
                        "replay");
        var local =
                new Reviewer(
                                github.source.git(),
                                ServicePolicy.resolve(
                                        github.files, settings.execution(), settings.sources()),
                                null,
                                null)
                        .run(new ReplayClient(List.of(replay)), temp.resolve("local"), "tools");
        JsonNode report = artifact.path("files").path("report.json");
        assertThat(report.path("findings")).isEqualTo(Json.MAPPER.valueToTree(local.findings()));
        assertThat(report.path("policy_hashes"))
                .isEqualTo(Json.MAPPER.valueToTree(local.policyHashes()));
        assertThat(report.path("status").asText()).isEqualTo(local.status());
        assertThat(
                        artifact.path("files")
                                .path("service-envelope.json")
                                .path("executionMode")
                                .asText())
                .isEqualTo("replay");
        HostedWorker.validate(manifest, github.source.root, artifact);
        ((ObjectNode) artifact.path("files").path("report.json")).put("summary", "tampered");
        assertThatThrownBy(() -> HostedWorker.validate(manifest, github.source.root, artifact))
                .hasMessage("Service artifact hash mismatch");
    }

    @Test
    void orchestratorUsesOneIsolatedWorkerAndPrivateArtifactAccessExpires() throws Exception {
        ObjectNode job = request(1, "manual-0001");
        AtomicInteger calls = new AtomicInteger();
        var pump =
                new ServiceOrchestrator(
                        control,
                        temp.resolve("workspaces"),
                        (repo, manifest, output) -> {
                            calls.incrementAndGet();
                            return HostedWorker.run(
                                    (ObjectNode) Json.parse(Json.read(manifest, 1500000)),
                                    repo,
                                    output,
                                    new ReplayClient(List.of()),
                                    "collect");
                        },
                        null,
                        "collect");
        assertThat(pump.tick()).isTrue();
        assertThat(pump.tick()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(
                        control.artifact(alice, tenantA, job.path("id").asText())
                                .path("files")
                                .has("evidence.json"))
                .isTrue();
        assertThatThrownBy(() -> control.artifact(bob, tenantB, job.path("id").asText()))
                .hasMessage("not_found");
        try (var paths = Files.list(temp.resolve("workspaces"))) {
            assertThat(paths.toList()).isEmpty();
        }
        clock.advance(Duration.ofDays(8));
        control.maintenance();
        assertThatThrownBy(() -> control.artifact(alice, tenantA, job.path("id").asText()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void durableRestartPreservesEncryptedCreditsAndJobs() throws Exception {
        String url = "jdbc:h2:file:" + temp.resolve("durable");
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        var durable = new ServiceDatabase(url, key);
        durable.transaction(
                db -> {
                    db.insert(
                            "test",
                            "one",
                            "tenant",
                            node().put("credential", "very-private-token"));
                    return null;
                });
        var reopened = new ServiceDatabase(url, key);
        assertThat(
                        reopened.transaction(db -> db.get("test", "one", "tenant"))
                                .path("credential")
                                .asText())
                .isEqualTo("very-private-token");
        assertThat(
                        new String(
                                Files.readAllBytes(temp.resolve("durable.mv.db")),
                                StandardCharsets.ISO_8859_1))
                .doesNotContain("very-private-token");
        var wrongKey = new ServiceDatabase(url, new byte[32]);
        assertThatThrownBy(() -> wrongKey.transaction(db -> db.get("test", "one", "tenant")))
                .isInstanceOf(javax.crypto.AEADBadTagException.class);
    }

    @Test
    void webhookSignatureIsCheckedBeforeParsingAndHttpErrorsAreSanitized() throws Exception {
        ServiceSecurity.verifyWebhook(
                "Hello, World!".getBytes(StandardCharsets.UTF_8),
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                "It's a Secret to Everybody");
        try (var server =
                new ServiceHttpServer(
                        new InetSocketAddress("127.0.0.1", 0),
                        URI.create("https://service.example"),
                        control,
                        null,
                        "w".repeat(32),
                        "o".repeat(32))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + server.port();
            var response =
                    client.send(
                            HttpRequest.newBuilder(URI.create(url + "/v1/webhooks/github"))
                                    .POST(HttpRequest.BodyPublishers.ofString("not json"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("invalid_signature");
            assertThat(database.<List<ObjectNode>>transaction(db -> db.list("delivery", "")))
                    .isEmpty();
            var home =
                    client.send(
                            HttpRequest.newBuilder(URI.create(url + "/")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(home.body()).contains("INTERNAL SERVICE PREVIEW");
            assertThat(home.headers().firstValue("Content-Security-Policy")).isPresent();
            var unauthorized =
                    client.send(
                            HttpRequest.newBuilder(URI.create(url + "/v1/credits")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);
            assertThat(unauthorized.body()).doesNotContain("fake-token", "SQLException");
        }
    }

    private ObjectNode event(String action, int pr) {
        ObjectNode event = node().put("action", action).put("number", pr);
        event.putObject("installation").put("id", 101);
        event.putObject("repository").put("id", 11);
        return event;
    }

    @Test
    void publicationPermitIsRevokedBySupersessionAndCannotCrossTenants() throws Exception {
        request(1, "manual-0001");
        ObjectNode worker = control.lease("one");
        control.startInference(worker, "collect");
        control.complete(worker, syntheticArtifact(), usage(true, 0, 0));
        ObjectNode publication = control.leasePublication("publisher");
        String permit = publication.path("publicationPermit").asText();
        control.checkPublication(
                tenantA,
                publication.path("id").asText(),
                publication.path("fence").asLong(),
                permit);
        assertThat(
                        control.review(alice, tenantA, publication.path("id").asText())
                                .has("publicationPermit"))
                .isFalse();
        assertThatThrownBy(
                        () ->
                                control.checkPublication(
                                        tenantB,
                                        publication.path("id").asText(),
                                        publication.path("fence").asLong(),
                                        permit))
                .hasMessage("not_found");
        request(1, "manual-0002");
        assertThatThrownBy(
                        () ->
                                control.checkPublication(
                                        tenantA,
                                        publication.path("id").asText(),
                                        publication.path("fence").asLong(),
                                        permit))
                .hasMessage("snapshot_changed");
    }

    @Test
    void meteringDoesNotWaitForAProviderThatIgnoresCancellation() throws Exception {
        request(1, "manual-0001");
        ObjectNode lease = control.lease("one");
        ObjectNode manifest = ServiceOrchestrator.manifest(lease, control.preparation(lease));
        ((ObjectNode) manifest.path("execution").path("limits")).put("run_timeout_seconds", 1);
        manifest.put(
                "effectivePolicy",
                ServicePolicy.effectiveHash(
                        ServicePolicy.resolve(
                                github.files,
                                manifest.path("execution"),
                                manifest.path("sources"))));
        var release = new java.util.concurrent.CountDownLatch(1);
        ModelClient provider =
                new ModelClient() {
                    @Override
                    public Turn next(
                            String instruction, List<Observation> observations, boolean finalOnly) {
                        while (release.getCount() != 0) {
                            try {
                                release.await();
                            } catch (InterruptedException ignored) {
                                /* Deliberately uncooperative provider fixture. */
                            }
                        }
                        return new Turn(List.of(), null, 100, 100, identity());
                    }

                    @Override
                    public String identity() {
                        return "authored-uncooperative-provider";
                    }
                };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var run =
                        executor.submit(
                                () ->
                                        HostedWorker.run(
                                                manifest,
                                                github.source.root,
                                                temp.resolve("bounded-worker"),
                                                provider,
                                                "live"));
                ObjectNode artifact = run.get(4, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(
                                artifact.path("files")
                                        .path("service-envelope.json")
                                        .path("usage")
                                        .path("known")
                                        .asBoolean())
                        .isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void topUpRechecksFreshAutomaticSnapshotsAndCoalescesObsoleteQueuedWork() throws Exception {
        clock.advance(Duration.ofDays(31));
        control.receive("blocked-review", "pull_request", event("opened", 1));
        control.processEvents();
        assertThat(control.history(alice, tenantA, "11").getFirst().path("reason").asText())
                .isEqualTo("insufficient_credits");
        control.grant(
                tenantA,
                RESERVATION,
                clock.millis() + Duration.ofDays(1).toMillis(),
                "topup-review",
                "operator",
                "new pilot allocation");
        control.processEvents();
        assertThat(control.history(alice, tenantA, "11"))
                .anySatisfy(j -> assertThat(j.path("admission").asText()).isEqualTo("accepted"));
        github.source.write("src/Pay.java", "class Pay { int amount() { return 3; } }\n");
        github.source.commit();
        control.receive("advanced-head", "pull_request", event("synchronize", 1));
        control.processEvents();
        ObjectNode latest =
                control.history(alice, tenantA, "11").stream()
                        .filter(j -> j.path("latest").asBoolean())
                        .findFirst()
                        .orElseThrow();
        assertThat(latest.path("admission").asText()).isEqualTo("accepted");
        assertThat(latest.path("snapshot").path("head").asText()).isEqualTo(github.source.head);
        assertThat(available()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-10-06T00:00:00Z");

        void advance(Duration duration) {
            time = time.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return time;
        }
    }

    private static final class FakeGithub implements ServiceGateway {
        private final TestRepository source;
        private final Map<String, String> files;
        private final Map<String, Set<Long>> access = new HashMap<>();
        private final Map<Long, String> installationOwners = new HashMap<>();
        private final Map<Long, Long> installationAccounts = new HashMap<>();
        private boolean internal = true;
        private boolean draft;

        FakeGithub(TestRepository source) throws Exception {
            this.source = source;
            files =
                    Map.of(
                            ".undertow/rules.yaml",
                            Files.readString(Path.of("config/languages/java/rules.yaml")),
                            ".undertow/guidelines.md",
                            Files.readString(Path.of("CODING-SKILL.md")),
                            ".undertow/business-context.yaml",
                            Files.readString(Path.of("config/business-context.yaml")));
            access.put("fake-token-1", Set.of(11L, 12L));
            access.put("fake-token-2", Set.of(22L));
            access.put("fake-token-3", Set.of(11L, 22L));
            installationOwners.put(101L, "1");
            installationOwners.put(202L, "2");
            installationAccounts.put(101L, 101L);
            installationAccounts.put(202L, 202L);
        }

        @Override
        public ObjectNode signIn(String code) {
            return node().put("id", Long.parseLong(code))
                    .put("login", "test-" + code)
                    .put("token", "fake-token-" + code);
        }

        @Override
        public boolean userAccess(String token, long repo, boolean write) {
            return access.getOrDefault(token, Set.of()).contains(repo);
        }

        @Override
        public ObjectNode installation(String token, long id) {
            return node().put("id", id)
                    .put("accountId", installationAccounts.getOrDefault(id, id))
                    .put("canAdminister", token.equals("fake-token-" + installationOwners.get(id)));
        }

        @Override
        public ObjectNode repository(long installation, long repo) {
            long account = repo == 22 ? 202 : 101;
            if (installationAccounts.getOrDefault(installation, installation) != account)
                throw new ServiceException(403, "installation_repository_denied");
            return node().put("id", repo)
                    .put("accountId", account)
                    .put("name", "customer/repo-" + repo);
        }

        @Override
        public ObjectNode snapshot(long installation, long repo, int pr) {
            return node().put("repositoryId", repo)
                    .put("accountId", repo == 22 ? 202 : 101)
                    .put("name", "customer/repo-" + repo)
                    .put("pr", pr)
                    .put("head", source.head)
                    .put("target", source.base)
                    .put("diffBase", source.base)
                    .put("policyCommit", source.base)
                    .put("branch", "main")
                    .put("open", true)
                    .put("draft", draft)
                    .put("internal", internal)
                    .put("maintainerAuthor", true)
                    .put("ciIdentity", "missing");
        }

        @Override
        public Map<String, String> policyFiles(long installation, long repo, String commit) {
            return files;
        }

        @Override
        public void prepare(long installation, long repo, ObjectNode snapshot, Path destination)
                throws Exception {
            source.command("clone", "--bare", source.root.toString(), destination.toString());
        }
    }
}
