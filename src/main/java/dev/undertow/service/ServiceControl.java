package dev.undertow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undertow.harness.Reviewer;
import dev.undertow.reporting.Json;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** All customer mutations pass through live authority checks and a durable transaction. */
public final class ServiceControl {
    public record Settings(
            String engineDigest,
            String executionVersion,
            String tariffVersion,
            long inputCreditRate,
            long outputCreditRate,
            long maximumReservation,
            int globalConcurrency,
            int tenantConcurrency,
            boolean liveApproved,
            JsonNode execution,
            JsonNode sources) {
        public Settings {
            if (!engineDigest.matches("sha256:[a-f0-9]{64}")
                    || executionVersion.isBlank()
                    || tariffVersion.isBlank()
                    || inputCreditRate < 0
                    || outputCreditRate < 0
                    || maximumReservation
                            < Math.multiplyExact(
                                    execution.path("limits").path("max_total_tokens").asLong(80000),
                                    Math.max(inputCreditRate, outputCreditRate))
                    || maximumReservation < 1
                    || globalConcurrency < 1
                    || tenantConcurrency < 1)
                throw new IllegalArgumentException("Invalid operator execution settings");
            execution = execution.deepCopy();
            sources = sources.deepCopy();
        }

        @Override
        public JsonNode execution() {
            return execution.deepCopy();
        }

        @Override
        public JsonNode sources() {
            return sources.deepCopy();
        }
    }

    public record Session(String user, String token, String csrf) {
        @Override
        public String toString() {
            return "Session[user=" + user + "]";
        }
    }

    private static final Set<String> ROLES =
            Set.of("administrator", "rule_owner", "reviewer", "billing_viewer");
    private final ServiceDatabase database;
    private final ServiceGateway github;
    private final Settings settings;
    private final Clock clock;

    public ServiceControl(
            ServiceDatabase database, ServiceGateway github, Settings settings, Clock clock) {
        this.database = database;
        this.github = github;
        this.settings = settings;
        this.clock = clock;
    }

    public Settings settings() {
        return settings;
    }

    public ServiceGateway gateway() {
        return github;
    }

    public void verifyExecutionAccess(ObjectNode lease) throws Exception {
        ObjectNode repository =
                database.transaction(
                        db -> {
                            ObjectNode job = activeLease(db, lease);
                            String tenant = job.path("tenant").asText();
                            check(job.path("latest").asBoolean(), 409, "snapshot_changed");
                            ObjectNode owner = required(db.get("tenant", tenant, tenant));
                            check(
                                    owner.path("state").asText().equals("active"),
                                    403,
                                    "tenant_disabled");
                            ObjectNode repo =
                                    required(
                                            db.get(
                                                    "repository",
                                                    job.path("repository").asText(),
                                                    tenant));
                            check(
                                    repo.path("active").asBoolean()
                                            && repo.path("enabled").asBoolean(),
                                    403,
                                    "repository_revoked");
                            if (!job.path("actor").asText().equals("github")) {
                                ObjectNode membership =
                                        db.get(
                                                "member",
                                                tenant + ":" + job.path("actor").asText(),
                                                tenant);
                                check(
                                        membership != null && hasRole(membership, "reviewer"),
                                        403,
                                        "tenant_access_denied");
                            }
                            return repo;
                        });
        if (!lease.path("actor").asText().equals("github")) {
            List<ObjectNode> sessions =
                    database.transaction(
                            db ->
                                    db.list("session", "").stream()
                                            .filter(
                                                    s ->
                                                            s.path("user")
                                                                            .asText()
                                                                            .equals(
                                                                                    lease.path(
                                                                                                    "actor")
                                                                                            .asText())
                                                                    && s.path("expires").asLong()
                                                                            > now())
                                            .toList());
            boolean allowed = false;
            for (ObjectNode session : sessions)
                if (github.userAccess(
                        session.path("token").asText(),
                        Long.parseLong(lease.path("repository").asText()),
                        true)) {
                    allowed = true;
                    break;
                }
            check(allowed, 403, "github_access_denied");
        }
        ObjectNode current = liveSnapshot(repository, lease.path("pr").asInt());
        check(
                Json.stringify(current).equals(Json.stringify(lease.path("snapshot"))),
                409,
                "snapshot_changed");
    }

    public ObjectNode executionRepository(ObjectNode lease) throws Exception {
        return database.transaction(
                db -> {
                    activeLease(db, lease);
                    return required(
                            db.get(
                                    "repository",
                                    lease.path("repository").asText(),
                                    lease.path("tenant").asText()));
                });
    }

    private ObjectNode liveSnapshot(ObjectNode repository, int pr) throws Exception {
        ObjectNode snapshot =
                github.snapshot(
                        repository.path("installation").asLong(),
                        repository.path("id").asLong(),
                        pr);
        if (repository.path("ciWorkflowId").asLong() > 0) {
            ObjectNode provenance =
                    github.ciProvenance(
                            repository.path("installation").asLong(),
                            repository.path("id").asLong(),
                            repository.path("ciWorkflowId").asLong(),
                            snapshot.path("head").asText());
            snapshot.set("ciOrigin", provenance);
            snapshot.put("ciIdentity", Json.hash(Json.stringify(provenance)));
        }
        return snapshot;
    }

    public ObjectNode leasePublication(String publisher) throws Exception {
        return database.transaction(
                db -> {
                    maintenance(db);
                    ObjectNode global = db.get("control", "global", "");
                    if (global != null && global.path("disabled").asBoolean()) return null;
                    for (ObjectNode job : db.list("review", null)) {
                        if (!job.path("job").asText().equals("finished")
                                || !job.path("latest").asBoolean()
                                || !Set.of("not_requested", "failed")
                                        .contains(job.path("publication").asText())
                                || job.path("publicationAttempts").asInt() >= 3
                                || job.path("publicationAfter").asLong() > now()) continue;
                        String tenant = job.path("tenant").asText(),
                                review = job.path("id").asText();
                        if (db.get("artifact", review, tenant) == null) continue;
                        ObjectNode owner = required(db.get("tenant", tenant, tenant));
                        if (!owner.path("state").asText().equals("active")) continue;
                        String lockId =
                                tenant
                                        + ":"
                                        + job.path("repository").asText()
                                        + ":"
                                        + job.path("pr").asInt();
                        ObjectNode lock = db.get("publication_lock", lockId, tenant);
                        if (lock != null && lock.path("expires").asLong() > now()) continue;
                        job.put("job", "publishing")
                                .put("worker", publisher)
                                .put("fence", job.path("fence").asLong() + 1)
                                .put("leaseUntil", now() + Duration.ofMinutes(3).toMillis())
                                .put(
                                        "publicationAttempts",
                                        job.path("publicationAttempts").asInt() + 1);
                        db.put(
                                "publication_lock",
                                lockId,
                                tenant,
                                object().put("review", review)
                                        .put("fence", job.path("fence").asLong())
                                        .put("expires", job.path("leaseUntil").asLong()));
                        String permit = ServiceSecurity.randomToken();
                        job.put("publicationPermitHash", ServiceSecurity.digest(permit));
                        db.put("review", review, tenant, job);
                        job.put("publicationPermit", permit);
                        return job;
                    }
                    return null;
                });
    }

    public void checkPublication(String tenant, String review, long fence, String permit)
            throws Exception {
        check(permit != null && permit.length() == 43, 403, "publication_permit_denied");
        ObjectNode lease =
                database.transaction(
                        db -> {
                            ObjectNode job = required(db.get("review", review, tenant));
                            check(
                                    job.path("job").asText().equals("publishing")
                                            && job.path("fence").asLong() == fence
                                            && ServiceSecurity.matches(
                                                    job.path("publicationPermitHash").asText(),
                                                    ServiceSecurity.digest(permit)),
                                    403,
                                    "publication_permit_denied");
                            return job;
                        });
        verifyExecutionAccess(lease);
        database.transaction(
                db -> {
                    ObjectNode job = activeLease(db, lease);
                    check(job.path("latest").asBoolean(), 409, "snapshot_changed");
                    ObjectNode global = db.get("control", "global", "");
                    check(
                            global == null || !global.path("disabled").asBoolean(),
                            403,
                            "service_disabled");
                    return null;
                });
    }

    public ObjectNode publicationArtifact(ObjectNode lease) throws Exception {
        return database.transaction(
                db -> {
                    activeLease(db, lease);
                    return required(
                            db.get(
                                    "artifact",
                                    lease.path("id").asText(),
                                    lease.path("tenant").asText()));
                });
    }

    public void publicationResult(ObjectNode lease, ObjectNode receipt) throws Exception {
        database.transaction(
                db -> {
                    ObjectNode job = activeLease(db, lease);
                    check(
                            job.path("job").asText().equals("publishing"),
                            409,
                            "invalid_job_transition");
                    String state = receipt.path("status").asText("failed");
                    check(
                            Set.of("published", "failed", "stale").contains(state),
                            422,
                            "invalid_publication_status");
                    if (!job.path("latest").asBoolean()) state = "stale";
                    job.put("job", "finished")
                            .put("publication", state)
                            .put("publicationAfter", now() + Duration.ofMinutes(1).toMillis());
                    String tenant = job.path("tenant").asText(), review = job.path("id").asText();
                    db.insert(
                            "publication",
                            id(),
                            tenant,
                            object().put("review", review)
                                    .put("attempt", job.path("publicationAttempts").asInt())
                                    .put("created", now())
                                    .set("receipt", receipt));
                    db.delete(
                            "publication_lock",
                            tenant
                                    + ":"
                                    + job.path("repository").asText()
                                    + ":"
                                    + job.path("pr").asInt(),
                            tenant);
                    db.put("review", review, tenant, job);
                    audit(db, tenant, "publisher", "publication_" + state, review);
                    return null;
                });
    }

    public long now() {
        return clock.millis();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static ObjectNode object() {
        return Json.MAPPER.createObjectNode();
    }

    private static ObjectNode required(ObjectNode value) {
        if (value == null) throw new ServiceException(404, "not_found");
        return value;
    }

    private static void check(boolean condition, int status, String code) {
        if (!condition) throw new ServiceException(status, code);
    }

    public String beginLogin() throws Exception {
        String state = ServiceSecurity.randomToken();
        database.transaction(
                db -> {
                    db.insert(
                            "oauth",
                            ServiceSecurity.digest(state),
                            "",
                            object().put("id", ServiceSecurity.digest(state))
                                    .put("expires", now() + Duration.ofMinutes(10).toMillis()));
                    return null;
                });
        return state;
    }

    public ObjectNode finishLogin(String state, String cookieState, String code) throws Exception {
        check(ServiceSecurity.matches(state, cookieState), 401, "invalid_oauth_state");
        database.transaction(
                db -> {
                    String digest = ServiceSecurity.digest(state);
                    ObjectNode flow = required(db.get("oauth", digest, ""));
                    check(flow.path("expires").asLong() > now(), 401, "expired_oauth_state");
                    db.delete("oauth", digest, "");
                    return null;
                });
        ObjectNode identity = github.signIn(code);
        check(
                identity.path("id").asLong() > 0 && !identity.path("token").asText().isBlank(),
                401,
                "invalid_identity");
        String user = identity.path("id").asText();
        String token = ServiceSecurity.randomToken(), csrf = ServiceSecurity.randomToken();
        database.transaction(
                db -> {
                    db.put(
                            "user",
                            user,
                            "",
                            object().put("id", user).put("login", identity.path("login").asText()));
                    long expiry =
                            Math.min(
                                    now() + Duration.ofHours(8).toMillis(),
                                    identity.path("expires").asLong(Long.MAX_VALUE));
                    db.insert(
                            "session",
                            ServiceSecurity.digest(token),
                            "",
                            object().put("id", ServiceSecurity.digest(token))
                                    .put("user", user)
                                    .put("token", identity.path("token").asText())
                                    .put("csrf", csrf)
                                    .put("expires", expiry));
                    return null;
                });
        return object().put("session", token).put("csrf", csrf).put("user", user);
    }

    public Session session(String cookie, String csrf, boolean mutation) throws Exception {
        check(cookie != null && !cookie.isBlank(), 401, "sign_in_required");
        ObjectNode value =
                database.transaction(db -> db.get("session", ServiceSecurity.digest(cookie), ""));
        check(value != null && value.path("expires").asLong() > now(), 401, "session_expired");
        if (mutation)
            check(ServiceSecurity.matches(value.path("csrf").asText(), csrf), 403, "invalid_csrf");
        return new Session(
                value.path("user").asText(),
                value.path("token").asText(),
                value.path("csrf").asText());
    }

    public void logout(String cookie) throws Exception {
        database.transaction(
                db -> {
                    db.delete("session", ServiceSecurity.digest(cookie), "");
                    return null;
                });
    }

    private ObjectNode member(ServiceDatabase.View db, Session session, String tenant, String role)
            throws Exception {
        ObjectNode owner = required(db.get("tenant", tenant, tenant));
        check(owner.path("state").asText().equals("active"), 403, "tenant_disabled");
        ObjectNode membership = db.get("member", tenant + ":" + session.user(), tenant);
        check(membership != null, 403, "tenant_access_denied");
        if (role != null) check(hasRole(membership, role), 403, "role_denied");
        return membership;
    }

    private static boolean hasRole(ObjectNode membership, String role) {
        if (role.equals("source_reader"))
            return hasRole(membership, "reviewer") || hasRole(membership, "rule_owner");
        for (JsonNode value : membership.path("roles"))
            if (value.asText().equals(role) || value.asText().equals("administrator")) return true;
        return false;
    }

    private ObjectNode authorizedRepository(
            Session session, String tenant, String repo, String role, boolean write)
            throws Exception {
        ObjectNode result =
                database.transaction(
                        db -> {
                            member(db, session, tenant, role);
                            ObjectNode repository = required(db.get("repository", repo, tenant));
                            check(repository.path("active").asBoolean(), 403, "repository_revoked");
                            return repository;
                        });
        check(
                github.userAccess(session.token(), Long.parseLong(repo), write),
                403,
                "github_access_denied");
        ObjectNode current =
                github.repository(result.path("installation").asLong(), Long.parseLong(repo));
        check(
                current.path("accountId").asLong() == result.path("accountId").asLong(),
                403,
                "repository_transferred");
        return result;
    }

    public List<ObjectNode> tenants(Session session) throws Exception {
        return database.transaction(
                db ->
                        db.list("member", null).stream()
                                .filter(m -> m.path("user").asText().equals(session.user()))
                                .map(
                                        m ->
                                                object().put("id", m.path("tenant").asText())
                                                        .<ObjectNode>set("roles", m.path("roles")))
                                .toList());
    }

    public ObjectNode connect(Session session, long installationId) throws Exception {
        ObjectNode installation = github.installation(session.token(), installationId);
        check(installation.path("canAdminister").asBoolean(), 403, "installation_authority_denied");
        long account = installation.path("accountId").asLong();
        check(account > 0, 422, "invalid_installation");
        return database.transaction(
                db -> {
                    ObjectNode existing = db.get("installation", Long.toString(installationId), "");
                    if (existing != null) {
                        member(db, session, existing.path("tenant").asText(), "administrator");
                        return object().put("tenant", existing.path("tenant").asText());
                    }
                    for (ObjectNode mapped : db.list("installation", ""))
                        check(
                                mapped.path("accountId").asLong() != account,
                                409,
                                "account_already_connected");
                    String tenant = id();
                    db.insert(
                            "tenant",
                            tenant,
                            tenant,
                            object().put("id", tenant)
                                    .put("accountId", account)
                                    .put("state", "active"));
                    ObjectNode membership =
                            object().put("tenant", tenant).put("user", session.user());
                    membership
                            .putArray("roles")
                            .add("administrator")
                            .add("rule_owner")
                            .add("reviewer")
                            .add("billing_viewer");
                    db.insert("member", tenant + ":" + session.user(), tenant, membership);
                    db.insert(
                            "installation",
                            Long.toString(installationId),
                            "",
                            object().put("id", installationId)
                                    .put("tenant", tenant)
                                    .put("accountId", account)
                                    .put("active", true));
                    audit(
                            db,
                            tenant,
                            session.user(),
                            "installation_connected",
                            Long.toString(installationId));
                    return object().put("tenant", tenant);
                });
    }

    public void membership(Session session, String tenant, String user, JsonNode roles)
            throws Exception {
        check(roles.isArray() && roles.size() <= 4, 422, "invalid_roles");
        for (JsonNode role : roles) check(ROLES.contains(role.asText()), 422, "invalid_roles");
        database.transaction(
                db -> {
                    member(db, session, tenant, "administrator");
                    required(db.get("user", user, ""));
                    check(!user.equals(session.user()), 409, "cannot_change_own_membership");
                    if (roles.isEmpty()) db.delete("member", tenant + ":" + user, tenant);
                    else
                        db.put(
                                "member",
                                tenant + ":" + user,
                                tenant,
                                object().put("tenant", tenant)
                                        .put("user", user)
                                        .set("roles", roles));
                    audit(db, tenant, session.user(), "membership_changed", user);
                    return null;
                });
    }

    public ObjectNode registerRepository(Session session, String tenant, long repoId)
            throws Exception {
        ObjectNode mapping =
                database.transaction(
                        db -> {
                            member(db, session, tenant, "administrator");
                            return db.list("installation", "").stream()
                                    .filter(
                                            i ->
                                                    i.path("tenant").asText().equals(tenant)
                                                            && i.path("active").asBoolean())
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    new ServiceException(
                                                            403, "installation_revoked"));
                        });
        check(github.userAccess(session.token(), repoId, true), 403, "github_access_denied");
        ObjectNode current = github.repository(mapping.path("id").asLong(), repoId);
        check(
                current.path("accountId").asLong() == mapping.path("accountId").asLong(),
                403,
                "repository_transferred");
        return database.transaction(
                db -> {
                    member(db, session, tenant, "administrator");
                    ObjectNode old = db.get("repository", Long.toString(repoId), tenant);
                    if (old != null) return old;
                    ObjectNode repository =
                            object().put("id", repoId)
                                    .put("tenant", tenant)
                                    .put("installation", mapping.path("id").asLong())
                                    .put("accountId", mapping.path("accountId").asLong())
                                    .put("name", current.path("name").asText())
                                    .put("active", true)
                                    .put("enabled", false)
                                    .put("policySource", "repository")
                                    .put("policy", "")
                                    .put("monthlyLimit", settings.maximumReservation() * 100)
                                    .put("reviewLimit", settings.maximumReservation());
                    repository.putArray("branches").add("main");
                    db.insert("repository", Long.toString(repoId), tenant, repository);
                    audit(
                            db,
                            tenant,
                            session.user(),
                            "repository_registered",
                            Long.toString(repoId));
                    return repository;
                });
    }

    public List<ObjectNode> repositories(Session session, String tenant) throws Exception {
        List<ObjectNode> values =
                database.transaction(
                        db -> {
                            ObjectNode membership = member(db, session, tenant, null);
                            check(
                                    hasRole(membership, "reviewer")
                                            || hasRole(membership, "rule_owner"),
                                    403,
                                    "role_denied");
                            return db.list("repository", tenant);
                        });
        var result = new java.util.ArrayList<ObjectNode>();
        for (ObjectNode repo : values)
            if (github.userAccess(session.token(), repo.path("id").asLong(), false))
                result.add(repo);
        return List.copyOf(result);
    }

    public ObjectNode configure(Session session, String tenant, String repo, ObjectNode input)
            throws Exception {
        authorizedRepository(session, tenant, repo, "administrator", true);
        check(
                Set.of(
                                "enabled",
                                "consent",
                                "policySource",
                                "policy",
                                "monthlyLimit",
                                "reviewLimit",
                                "branches",
                                "ciWorkflowId")
                        .containsAll(keys(input)),
                422,
                "unknown_settings");
        ObjectNode configured =
                database.transaction(
                        db -> {
                            member(db, session, tenant, "administrator");
                            ObjectNode value = required(db.get("repository", repo, tenant));
                            String priorSettings = reviewSettingsIdentity(value);
                            if (input.has("enabled")) {
                                check(
                                        !input.path("enabled").asBoolean()
                                                || input.path("consent").asBoolean(),
                                        422,
                                        "consent_required");
                                value.put("enabled", input.path("enabled").asBoolean())
                                        .put("consentAt", now())
                                        .put("consentActor", session.user());
                            }
                            String source =
                                    input.path("policySource")
                                            .asText(value.path("policySource").asText());
                            check(
                                    Set.of("repository", "central").contains(source),
                                    422,
                                    "invalid_policy_source");
                            value.put("policySource", source);
                            if (input.has("policy"))
                                value.put("policy", input.path("policy").asText());
                            if (source.equals("central"))
                                required(db.get("package", value.path("policy").asText(), tenant));
                            for (String limit : List.of("monthlyLimit", "reviewLimit"))
                                if (input.has(limit)) {
                                    long amount = input.path(limit).asLong(-1);
                                    check(
                                            amount > 0 && amount <= 1000000000L,
                                            422,
                                            "invalid_limit");
                                    value.put(limit, amount);
                                }
                            if (input.has("branches")) {
                                JsonNode branches = input.path("branches");
                                check(
                                        branches.isArray()
                                                && !branches.isEmpty()
                                                && branches.size() <= 20,
                                        422,
                                        "invalid_branches");
                                for (JsonNode branch : branches)
                                    check(
                                            branch.isTextual()
                                                    && branch.asText()
                                                            .matches("[A-Za-z0-9_./-]{1,150}")
                                                    && !branch.asText().contains(".."),
                                            422,
                                            "invalid_branches");
                                value.set("branches", branches);
                            }
                            if (input.has("ciWorkflowId")) {
                                check(
                                        input.path("ciWorkflowId").canConvertToLong()
                                                && input.path("ciWorkflowId").asLong() >= 0,
                                        422,
                                        "invalid_ci_workflow");
                                value.put("ciWorkflowId", input.path("ciWorkflowId").asLong());
                            }
                            db.put("repository", repo, tenant, value);
                            if (!priorSettings.equals(reviewSettingsIdentity(value)))
                                invalidate(db, tenant, repo, 0, "settings_changed");
                            audit(db, tenant, session.user(), "repository_settings", repo);
                            return value;
                        });
        if (configured.path("enabled").asBoolean()) scheduleRepository(configured);
        return configured;
    }

    private static String reviewSettingsIdentity(ObjectNode repository) throws java.io.IOException {
        ObjectNode settings = object();
        for (String key :
                List.of(
                        "enabled",
                        "policySource",
                        "policy",
                        "reviewLimit",
                        "monthlyLimit",
                        "branches",
                        "ciWorkflowId")) settings.set(key, repository.path(key));
        return Json.hash(Json.stringify(settings));
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> result = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    public ObjectNode createPackage(Session session, String tenant, ObjectNode input)
            throws Exception {
        database.transaction(
                db -> {
                    member(db, session, tenant, "rule_owner");
                    return null;
                });
        Map<String, String> files = ServicePolicy.files(input.path("files"));
        var policy = ServicePolicy.resolve(files, settings.execution(), settings.sources());
        String hash = Json.hash(Json.stringify(new java.util.TreeMap<>(files)));
        return database.transaction(
                db -> {
                    member(db, session, tenant, "rule_owner");
                    String packageId = tenant + ":" + hash;
                    ObjectNode existing = db.get("package", packageId, tenant);
                    if (existing != null) return object().put("id", packageId).put("hash", hash);
                    ObjectNode value =
                            object().put("id", packageId)
                                    .put("tenant", tenant)
                                    .put("hash", hash)
                                    .put("effectiveHash", ServicePolicy.effectiveHash(policy))
                                    .put("validator", "rules-v1-v2")
                                    .put("created", now());
                    value.set("files", Json.MAPPER.valueToTree(files));
                    db.insert("package", packageId, tenant, value);
                    audit(db, tenant, session.user(), "policy_validated", packageId);
                    return object().put("id", packageId).put("hash", hash);
                });
    }

    public ObjectNode requestReview(
            Session session, String tenant, String repo, int pr, String requestKey)
            throws Exception {
        authorizedRepository(session, tenant, repo, "reviewer", true);
        check(
                requestKey != null && requestKey.matches("[A-Za-z0-9_-]{8,100}"),
                422,
                "idempotency_key_required");
        String key = ServiceSecurity.digest(tenant + ":" + session.user() + ":" + requestKey);
        String payload = repo + ":" + pr;
        ObjectNode prior = database.transaction(db -> db.get("request", key, tenant));
        if (prior != null) {
            check(prior.path("payload").asText().equals(payload), 409, "idempotency_conflict");
            return review(session, tenant, prior.path("review").asText());
        }
        ObjectNode repository =
                database.transaction(db -> required(db.get("repository", repo, tenant)));
        return admit(repository, pr, "manual:" + key, session.user(), key, payload);
    }

    private ObjectNode admit(
            ObjectNode repository,
            int pr,
            String trigger,
            String actor,
            String requestKey,
            String payload)
            throws Exception {
        check(pr > 0, 422, "invalid_pr");
        String tenant = repository.path("tenant").asText(), repo = repository.path("id").asText();
        ObjectNode snapshot = liveSnapshot(repository, pr);
        check(
                snapshot.path("repositoryId").asText().equals(repo),
                403,
                "repository_identity_mismatch");
        check(
                snapshot.path("accountId").asLong() == repository.path("accountId").asLong(),
                403,
                "repository_transferred");
        boolean automatic = trigger.startsWith("automatic:");
        if (automatic)
            check(
                    snapshot.path("internal").asBoolean()
                            && snapshot.path("maintainerAuthor").asBoolean(),
                    403,
                    "maintainer_request_required");
        String policyIdentity = snapshot.path("target").asText();
        Map<String, String> files;
        if (repository.path("policySource").asText().equals("central")) {
            ObjectNode pack =
                    database.transaction(
                            db ->
                                    required(
                                            db.get(
                                                    "package",
                                                    repository.path("policy").asText(),
                                                    tenant)));
            files = ServicePolicy.files(pack.path("files"));
            policyIdentity = pack.path("id").asText();
        } else
            files =
                    github.policyFiles(
                            repository.path("installation").asLong(),
                            Long.parseLong(repo),
                            policyIdentity);
        var policy = ServicePolicy.resolve(files, settings.execution(), settings.sources());
        String effectiveHash = ServicePolicy.effectiveHash(policy);
        String logicalKey =
                Json.hash(
                        tenant
                                + ":github:github.com:"
                                + repo
                                + ":"
                                + pr
                                + ":"
                                + Json.stringify(snapshot)
                                + ":"
                                + policyIdentity
                                + ":"
                                + effectiveHash
                                + ":"
                                + settings.engineDigest()
                                + ":"
                                + settings.executionVersion()
                                + ":"
                                + Reviewer.PROMPT_VERSION
                                + ":"
                                + settings.tariffVersion()
                                + ":"
                                + reviewSettingsIdentity(repository)
                                + ":"
                                + trigger);
        String pinnedPolicy = policyIdentity;
        return database.transaction(
                db -> {
                    if (requestKey != null) {
                        ObjectNode existing = db.get("request", requestKey, tenant);
                        if (existing != null) {
                            check(
                                    existing.path("payload").asText().equals(payload),
                                    409,
                                    "idempotency_conflict");
                            return required(
                                    db.get("review", existing.path("review").asText(), tenant));
                        }
                    }
                    ObjectNode existing = db.get("review_key", logicalKey, tenant);
                    if (existing != null) {
                        ObjectNode prior =
                                required(
                                        db.get("review", existing.path("review").asText(), tenant));
                        boolean replenished =
                                automatic
                                        && prior.path("reason")
                                                .asText()
                                                .equals("insufficient_credits")
                                        && available(db, tenant) >= settings.maximumReservation();
                        if (!replenished) return prior;
                        prior.put("latest", false);
                        db.put("review", prior.path("id").asText(), tenant, prior);
                        db.delete("review_key", logicalKey, tenant);
                    }
                    ObjectNode current = required(db.get("repository", repo, tenant));
                    ObjectNode owner = required(db.get("tenant", tenant, tenant));
                    if (!actor.equals("github")) {
                        ObjectNode membership = db.get("member", tenant + ":" + actor, tenant);
                        check(
                                membership != null && hasRole(membership, "reviewer"),
                                403,
                                "tenant_access_denied");
                    }
                    check(
                            current.path("policySource").equals(repository.path("policySource"))
                                    && current.path("policy").equals(repository.path("policy")),
                            409,
                            "policy_changed");
                    expireGrants(db, tenant);
                    invalidate(db, tenant, repo, pr, "superseded");
                    String reason = admissionReason(db, current, owner, snapshot);
                    long reservation =
                            Math.min(
                                    settings.maximumReservation(),
                                    current.path("reviewLimit").asLong());
                    if (reason.isEmpty() && reservation < settings.maximumReservation())
                        reason = "review_budget_too_small";
                    if (reason.isEmpty() && available(db, tenant) < reservation)
                        reason = "insufficient_credits";
                    if (reason.isEmpty()
                            && periodUse(db, tenant, repo) + reservation
                                    > current.path("monthlyLimit").asLong())
                        reason = "repository_period_limit";
                    String reviewId = id();
                    ObjectNode job =
                            object().put("schemaVersion", 1)
                                    .put("id", reviewId)
                                    .put("tenant", tenant)
                                    .put("repository", repo)
                                    .put("pr", pr)
                                    .put("actor", actor)
                                    .put("created", now())
                                    .put("admission", reason.isEmpty() ? "accepted" : "rejected")
                                    .put("reason", reason)
                                    .put("job", reason.isEmpty() ? "queued" : "finished")
                                    .put("reviewOutcome", "not_run")
                                    .put("publication", "not_requested")
                                    .put("metering", reason.isEmpty() ? "reserved" : "released")
                                    .put("reserved", reason.isEmpty() ? reservation : 0)
                                    .put("settled", 0)
                                    .put("engineDigest", settings.engineDigest())
                                    .put("executionVersion", settings.executionVersion())
                                    .put("tariff", settings.tariffVersion())
                                    .put("inputRate", settings.inputCreditRate())
                                    .put("outputRate", settings.outputCreditRate())
                                    .put("effectivePolicy", effectiveHash)
                                    .put("policyIdentity", pinnedPolicy)
                                    .put("attempt", 0)
                                    .put("fence", 0)
                                    .put("leaseUntil", 0)
                                    .put("inferenceStarted", false)
                                    .put("latest", true)
                                    .put("executionMode", "pending");
                    job.set("snapshot", snapshot);
                    if (reason.isEmpty())
                        job.set("reservationAllocations", allocations(db, tenant, reservation));
                    db.insert("review", reviewId, tenant, job);
                    db.insert("review_key", logicalKey, tenant, object().put("review", reviewId));
                    if (requestKey != null)
                        db.insert(
                                "request",
                                requestKey,
                                tenant,
                                object().put("payload", payload).put("review", reviewId));
                    if (reason.isEmpty()) {
                        ObjectNode preparation =
                                object().put("review", reviewId)
                                        .put("tenant", tenant)
                                        .put("expires", now() + Duration.ofDays(1).toMillis());
                        preparation.set("files", Json.MAPPER.valueToTree(files));
                        preparation.set("execution", settings.execution());
                        preparation.set("sources", settings.sources());
                        db.insert("preparation", reviewId, tenant, preparation);
                        ledger(
                                db,
                                tenant,
                                reviewId,
                                "reserve",
                                reservation,
                                actor,
                                "review_admission",
                                0);
                    }
                    audit(db, tenant, actor, "review_admission", reviewId);
                    return job;
                });
    }

    private String admissionReason(
            ServiceDatabase.View db, ObjectNode repository, ObjectNode tenant, ObjectNode snapshot)
            throws Exception {
        ObjectNode global = db.get("control", "global", "");
        if (global != null && global.path("disabled").asBoolean()) return "service_disabled";
        if (!tenant.path("state").asText().equals("active")) return "tenant_disabled";
        if (!repository.path("active").asBoolean()) return "repository_revoked";
        if (!repository.path("enabled").asBoolean()) return "repository_disabled";
        if (!snapshot.path("open").asBoolean() || snapshot.path("draft").asBoolean())
            return "pr_ineligible";
        boolean branch = false;
        for (JsonNode value : repository.path("branches"))
            if (value.asText().equals(snapshot.path("branch").asText())) branch = true;
        return branch ? "" : "target_branch_disabled";
    }

    public ObjectNode review(Session session, String tenant, String reviewId) throws Exception {
        ObjectNode value =
                database.transaction(
                        db -> {
                            member(db, session, tenant, "source_reader");
                            return required(db.get("review", reviewId, tenant));
                        });
        authorizedRepository(
                session, tenant, value.path("repository").asText(), "source_reader", false);
        return value;
    }

    public List<ObjectNode> history(Session session, String tenant, String repo) throws Exception {
        authorizedRepository(session, tenant, repo, "source_reader", false);
        return database.transaction(
                db ->
                        db.list("review", tenant).stream()
                                .filter(j -> j.path("repository").asText().equals(repo))
                                .sorted(
                                        Comparator.comparingLong(
                                                        (ObjectNode j) ->
                                                                j.path("created").asLong())
                                                .reversed())
                                .limit(100)
                                .toList());
    }

    public ObjectNode artifact(Session session, String tenant, String reviewId) throws Exception {
        review(session, tenant, reviewId);
        return database.transaction(
                db -> {
                    member(db, session, tenant, "source_reader");
                    ObjectNode artifact = required(db.get("artifact", reviewId, tenant));
                    check(artifact.path("expires").asLong() > now(), 410, "artifact_expired");
                    return artifact;
                });
    }

    public void feedback(Session session, String tenant, String reviewId, ObjectNode input)
            throws Exception {
        review(session, tenant, reviewId);
        String finding = input.path("finding").asText(),
                assessment = input.path("assessment").asText();
        check(
                Set.of("useful", "false_positive", "incorrect_severity", "correction")
                        .contains(assessment),
                422,
                "invalid_assessment");
        ObjectNode stored = artifact(session, tenant, reviewId);
        JsonNode report = stored.path("files").path("report.json");
        boolean exists = false;
        for (JsonNode f : report.path("findings"))
            if (f.path("id").asText().equals(finding)) exists = true;
        check(exists, 422, "unknown_finding");
        String note = input.path("note").asText();
        check(note.length() <= 2000, 422, "feedback_too_large");
        database.transaction(
                db -> {
                    member(db, session, tenant, "reviewer");
                    String feedbackId = id();
                    db.insert(
                            "feedback",
                            feedbackId,
                            tenant,
                            object().put("id", feedbackId)
                                    .put("tenant", tenant)
                                    .put("review", reviewId)
                                    .put("finding", finding)
                                    .put("assessment", assessment)
                                    .put("expires", now() + Duration.ofDays(7).toMillis())
                                    .put("note", note)
                                    .put("actor", session.user())
                                    .put("created", now()));
                    audit(db, tenant, session.user(), "feedback", reviewId);
                    return null;
                });
    }

    private void audit(
            ServiceDatabase.View db, String tenant, String actor, String action, String resource)
            throws Exception {
        db.insert(
                "audit",
                id(),
                tenant,
                object().put("tenant", tenant)
                        .put("actor", actor)
                        .put("action", action)
                        .put("resource", resource)
                        .put("created", now()));
    }

    private void ledger(
            ServiceDatabase.View db,
            String tenant,
            String reviewId,
            String type,
            long amount,
            String actor,
            String reason,
            long expires)
            throws Exception {
        String entryId = id();
        ObjectNode entry =
                object().put("id", entryId)
                        .put("tenant", tenant)
                        .put("review", reviewId)
                        .put("type", type)
                        .put("amount", amount)
                        .put("actor", actor)
                        .put("reason", reason)
                        .put("created", now())
                        .put("expires", expires)
                        .put("tariff", settings.tariffVersion());
        db.insert("ledger", entryId, tenant, entry);
    }

    private long grantAllocated(ServiceDatabase.View db, String tenant, String grant)
            throws Exception {
        long used = 0;
        for (ObjectNode job : db.list("review", tenant)) {
            used = Math.addExact(used, job.path("settlementAllocations").path(grant).asLong());
            if (Set.of("reserved", "reconciliation_required")
                    .contains(job.path("metering").asText()))
                used = Math.addExact(used, job.path("reservationAllocations").path(grant).asLong());
        }
        return used;
    }

    private List<ObjectNode> grants(ServiceDatabase.View db, String tenant) throws Exception {
        return db.list("ledger", tenant).stream()
                .filter(e -> e.path("type").asText().equals("grant"))
                .sorted(Comparator.comparingLong(e -> e.path("expires").asLong()))
                .toList();
    }

    private long available(ServiceDatabase.View db, String tenant) throws Exception {
        long balance = 0;
        for (ObjectNode grant : grants(db, tenant))
            if (grant.path("expires").asLong() > now())
                balance =
                        Math.addExact(
                                balance,
                                grant.path("amount").asLong()
                                        - grantAllocated(db, tenant, grant.path("id").asText()));
        return balance;
    }

    private ObjectNode allocations(ServiceDatabase.View db, String tenant, long amount)
            throws Exception {
        ObjectNode result = object();
        for (ObjectNode grant : grants(db, tenant)) {
            if (grant.path("expires").asLong() <= now()) continue;
            long unused =
                    grant.path("amount").asLong()
                            - grantAllocated(db, tenant, grant.path("id").asText());
            long take = Math.min(amount, unused);
            if (take > 0) result.put(grant.path("id").asText(), take);
            amount -= take;
            if (amount == 0) break;
        }
        check(amount == 0, 409, "insufficient_credits");
        return result;
    }

    private long periodUse(ServiceDatabase.View db, String tenant, String repo) throws Exception {
        var period = java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneOffset.UTC));
        long amount = 0;
        for (ObjectNode job : db.list("review", tenant))
            if (job.path("repository").asText().equals(repo)
                    && java.time.YearMonth.from(
                                    java.time.Instant.ofEpochMilli(job.path("created").asLong())
                                            .atZone(java.time.ZoneOffset.UTC))
                            .equals(period)) {
                amount = Math.addExact(amount, job.path("settled").asLong());
                if (Set.of("reserved", "reconciliation_required")
                        .contains(job.path("metering").asText()))
                    amount = Math.addExact(amount, job.path("reserved").asLong());
            }
        return amount;
    }

    public ObjectNode credits(Session session, String tenant) throws Exception {
        return database.transaction(
                db -> {
                    member(db, session, tenant, "billing_viewer");
                    expireGrants(db, tenant);
                    return object().put("available", available(db, tenant))
                            .set("ledger", Json.MAPPER.valueToTree(db.list("ledger", tenant)));
                });
    }

    public List<ObjectNode> usage(Session session, String tenant) throws Exception {
        return database.transaction(
                db -> {
                    member(db, session, tenant, "billing_viewer");
                    return db.list("review", tenant).stream()
                            .map(
                                    j ->
                                            object().put("review", j.path("id").asText())
                                                    .put("created", j.path("created").asLong())
                                                    .put("job", j.path("job").asText())
                                                    .put(
                                                            "reviewOutcome",
                                                            j.path("reviewOutcome").asText())
                                                    .put("metering", j.path("metering").asText())
                                                    .put("reserved", j.path("reserved").asLong())
                                                    .put("settled", j.path("settled").asLong())
                                                    .put("reason", j.path("reason").asText())
                                                    .put("tariff", j.path("tariff").asText())
                                                    .<ObjectNode>set("usage", j.path("usage")))
                            .toList();
                });
    }

    public void grant(
            String tenant,
            long amount,
            long expires,
            String requestKey,
            String operator,
            String reason)
            throws Exception {
        check(
                amount > 0
                        && amount <= 1000000000L
                        && expires > now()
                        && reason.matches("[A-Za-z0-9_ -]{3,200}"),
                422,
                "invalid_grant");
        String key = Json.hash(tenant + ":grant:" + requestKey),
                payload = amount + ":" + expires + ":" + reason;
        database.transaction(
                db -> {
                    required(db.get("tenant", tenant, tenant));
                    ObjectNode prior = db.get("operator_request", key, tenant);
                    if (prior != null) {
                        check(
                                prior.path("payload").asText().equals(payload),
                                409,
                                "idempotency_conflict");
                        return null;
                    }
                    db.insert("operator_request", key, tenant, object().put("payload", payload));
                    ledger(db, tenant, "", "grant", amount, operator, reason, expires);
                    audit(db, tenant, operator, "credit_grant", key);
                    return null;
                });
    }

    private void expireGrants(ServiceDatabase.View db, String tenant) throws Exception {
        for (ObjectNode grant : grants(db, tenant))
            if (grant.path("expires").asLong() <= now()) {
                String grantId = grant.path("id").asText();
                long expired = grant.path("amount").asLong() - grantAllocated(db, tenant, grantId);
                ObjectNode prior = db.get("grant_expiry", grantId, tenant);
                long recorded = prior == null ? 0 : prior.path("expired").asLong();
                check(expired >= recorded, 500, "credit_invariant_failed");
                if (expired > recorded)
                    ledger(
                            db,
                            tenant,
                            "",
                            "expire",
                            expired - recorded,
                            "scheduler",
                            "grant_expired",
                            0);
                db.put("grant_expiry", grantId, tenant, object().put("expired", expired));
            }
        String period =
                java.time.YearMonth.from(clock.instant().atZone(java.time.ZoneOffset.UTC))
                        .toString();
        if (db.get("period", tenant + ":" + period, tenant) == null) {
            db.insert(
                    "period",
                    tenant + ":" + period,
                    tenant,
                    object().put("period", period).put("created", now()));
            audit(db, tenant, "scheduler", "billing_period_started", period);
        }
    }

    private void invalidate(
            ServiceDatabase.View db, String tenant, String repo, int pr, String reason)
            throws Exception {
        for (ObjectNode job : db.list("review", tenant))
            if (job.path("repository").asText().equals(repo)
                    && (pr == 0 || job.path("pr").asInt() == pr)) {
                job.put("latest", false);
                if (job.path("publication").asText().equals("published"))
                    job.put("publication", "stale");
                if (job.path("job").asText().equals("queued")) {
                    job.put("job", "cancelled").put("reason", reason);
                    release(db, job, "scheduler", reason);
                }
                db.put("review", job.path("id").asText(), tenant, job);
            }
    }

    private void release(ServiceDatabase.View db, ObjectNode job, String actor, String reason)
            throws Exception {
        if (!Set.of("reserved", "reconciliation_required").contains(job.path("metering").asText()))
            return;
        ledger(
                db,
                job.path("tenant").asText(),
                job.path("id").asText(),
                "release",
                job.path("reserved").asLong(),
                actor,
                reason,
                0);
        job.put("metering", "released").put("reserved", 0);
    }

    public ObjectNode lease(String worker) throws Exception {
        return database.transaction(
                db -> {
                    maintenance(db);
                    ObjectNode global = db.get("control", "global", "");
                    if (global != null && global.path("disabled").asBoolean()) return null;
                    List<ObjectNode> jobs = db.list("review", null);
                    long running =
                            jobs.stream()
                                    .filter(
                                            j ->
                                                    Set.of("preparing", "running", "publishing")
                                                            .contains(j.path("job").asText()))
                                    .count();
                    if (running >= settings.globalConcurrency()) return null;
                    List<ObjectNode> queued =
                            jobs.stream()
                                    .filter(
                                            j ->
                                                    j.path("job").asText().equals("queued")
                                                            && j.path("latest").asBoolean())
                                    .sorted(
                                            Comparator.comparingLong(
                                                    j -> j.path("created").asLong()))
                                    .toList();
                    for (ObjectNode job : queued) {
                        String tenant = job.path("tenant").asText();
                        long tenantRunning =
                                jobs.stream()
                                        .filter(
                                                j ->
                                                        j.path("tenant").asText().equals(tenant)
                                                                && Set.of(
                                                                                "preparing",
                                                                                "running",
                                                                                "publishing")
                                                                        .contains(
                                                                                j.path("job")
                                                                                        .asText()))
                                        .count();
                        if (tenantRunning >= settings.tenantConcurrency()) continue;
                        job.put("job", "preparing")
                                .put("worker", worker)
                                .put("attempt", job.path("attempt").asInt() + 1)
                                .put("fence", job.path("fence").asLong() + 1)
                                .put("leaseUntil", now() + Duration.ofMinutes(10).toMillis());
                        db.put("review", job.path("id").asText(), tenant, job);
                        return job;
                    }
                    return null;
                });
    }

    public ObjectNode preparation(ObjectNode lease) throws Exception {
        return database.transaction(
                db -> {
                    activeLease(db, lease);
                    return required(
                            db.get(
                                    "preparation",
                                    lease.path("id").asText(),
                                    lease.path("tenant").asText()));
                });
    }

    private ObjectNode activeLease(ServiceDatabase.View db, ObjectNode lease) throws Exception {
        ObjectNode current =
                required(
                        db.get("review", lease.path("id").asText(), lease.path("tenant").asText()));
        check(
                current.path("fence").asLong() == lease.path("fence").asLong()
                        && current.path("worker").asText().equals(lease.path("worker").asText())
                        && current.path("leaseUntil").asLong() > now()
                        && Set.of("preparing", "running", "publishing")
                                .contains(current.path("job").asText()),
                409,
                "lease_lost");
        return current;
    }

    public void startInference(ObjectNode lease, String mode) throws Exception {
        check(Set.of("replay", "live", "collect").contains(mode), 422, "invalid_execution_mode");
        check(!mode.equals("live") || settings.liveApproved(), 403, "live_processing_not_approved");
        database.transaction(
                db -> {
                    ObjectNode current = activeLease(db, lease);
                    ObjectNode repo =
                            required(
                                    db.get(
                                            "repository",
                                            current.path("repository").asText(),
                                            current.path("tenant").asText()));
                    ObjectNode tenant =
                            required(
                                    db.get(
                                            "tenant",
                                            current.path("tenant").asText(),
                                            current.path("tenant").asText()));
                    check(
                            current.path("latest").asBoolean()
                                    && admissionReason(
                                                    db,
                                                    repo,
                                                    tenant,
                                                    (ObjectNode) current.path("snapshot"))
                                            .isEmpty(),
                            403,
                            "review_revoked");
                    current.put("job", "running")
                            .put("executionMode", mode)
                            .put("inferenceStarted", mode.equals("live"));
                    // Persist BEFORE handing inference authority to the runner. A crash now is
                    // uncertain use.
                    db.put(
                            "review",
                            current.path("id").asText(),
                            current.path("tenant").asText(),
                            current);
                    return null;
                });
    }

    public void complete(ObjectNode lease, ObjectNode artifact, ObjectNode verifiedUsage)
            throws Exception {
        database.transaction(
                db -> {
                    ObjectNode current = activeLease(db, lease);
                    check(
                            current.path("job").asText().equals("running"),
                            409,
                            "invalid_job_transition");
                    String tenant = current.path("tenant").asText(),
                            review = current.path("id").asText();
                    ObjectNode owner = required(db.get("tenant", tenant, tenant));
                    check(!owner.path("state").asText().equals("deleted"), 403, "tenant_deleted");
                    artifact.put("expires", now() + Duration.ofDays(7).toMillis())
                            .put("review", review)
                            .put("tenant", tenant);
                    db.insert("artifact", review, tenant, artifact);
                    current.put("job", "finished")
                            .put("finished", now())
                            .put(
                                    "reviewOutcome",
                                    artifact.path("files")
                                            .path("report.json")
                                            .path("status")
                                            .asText());
                    current.set("usage", verifiedUsage);
                    if (current.path("inferenceStarted").asBoolean()
                            && !verifiedUsage.path("known").asBoolean()) {
                        current.put("metering", "reconciliation_required")
                                .put("reconcileBy", now() + Duration.ofHours(24).toMillis());
                    } else settle(db, current, verifiedUsage, "worker", "verified_usage");
                    db.put("review", review, tenant, current);
                    audit(db, tenant, "worker", "review_completed", review);
                    return null;
                });
    }

    private void settle(
            ServiceDatabase.View db, ObjectNode job, ObjectNode usage, String actor, String reason)
            throws Exception {
        if (!job.path("metering").asText().equals("reserved")
                && !job.path("metering").asText().equals("reconciliation_required")) return;
        long charge = 0;
        if (job.path("inferenceStarted").asBoolean()) {
            check(
                    usage.path("known").asBoolean()
                            && usage.path("inputTokens").asLong(-1) >= 0
                            && usage.path("outputTokens").asLong(-1) >= 0,
                    422,
                    "usage_unknown");
            charge =
                    Math.addExact(
                            Math.multiplyExact(
                                    usage.path("inputTokens").asLong(),
                                    job.path("inputRate").asLong()),
                            Math.multiplyExact(
                                    usage.path("outputTokens").asLong(),
                                    job.path("outputRate").asLong()));
        }
        if (charge > job.path("reserved").asLong()) {
            job.put("metering", "reconciliation_required")
                    .put("reason", "usage_exceeds_reservation")
                    .put("reconcileBy", now() + Duration.ofHours(24).toMillis());
            return;
        }
        ledger(
                db,
                job.path("tenant").asText(),
                job.path("id").asText(),
                "settle",
                charge,
                actor,
                reason,
                0);
        ledger(
                db,
                job.path("tenant").asText(),
                job.path("id").asText(),
                "release",
                job.path("reserved").asLong() - charge,
                actor,
                "unused_reservation",
                0);
        ObjectNode settledAllocations = object();
        long remaining = charge;
        var fields = job.path("reservationAllocations").fields();
        while (fields.hasNext()) {
            var allocation = fields.next();
            long take = Math.min(remaining, allocation.getValue().asLong());
            if (take > 0) settledAllocations.put(allocation.getKey(), take);
            remaining -= take;
        }
        check(remaining == 0, 500, "credit_invariant_failed");
        job.set("settlementAllocations", settledAllocations);
        job.put("settled", charge).put("reserved", 0).put("metering", "settled");
    }

    public void fail(ObjectNode lease, String reason) throws Exception {
        check(
                Set.of("preparation_failed", "worker_failed", "access_revoked", "snapshot_changed")
                        .contains(reason),
                422,
                "invalid_failure_reason");
        database.transaction(
                db -> {
                    ObjectNode current = activeLease(db, lease);
                    current.put("job", "failed").put("reason", reason).put("finished", now());
                    if (current.path("inferenceStarted").asBoolean())
                        current.put("metering", "reconciliation_required")
                                .put("reconcileBy", now() + Duration.ofHours(24).toMillis());
                    else release(db, current, "worker", reason);
                    db.put(
                            "review",
                            current.path("id").asText(),
                            current.path("tenant").asText(),
                            current);
                    return null;
                });
    }

    public void reconcile(
            String tenant, String review, ObjectNode usage, String operator, String reason)
            throws Exception {
        check(reason.matches("[A-Za-z0-9_ -]{3,200}"), 422, "invalid_reason");
        database.transaction(
                db -> {
                    ObjectNode job = required(db.get("review", review, tenant));
                    check(
                            job.path("metering").asText().equals("reconciliation_required"),
                            409,
                            "not_in_reconciliation");
                    if (usage.path("known").asBoolean()) {
                        settle(db, job, usage, operator, reason);
                        job.set("usage", usage);
                    } else release(db, job, operator, "service_absorbed_" + reason);
                    db.put("review", review, tenant, job);
                    audit(db, tenant, operator, "usage_reconciled", review);
                    return null;
                });
    }

    public void operatorControl(String tenant, String operator, String action, String reason)
            throws Exception {
        check(reason.matches("[A-Za-z0-9_ -]{3,200}"), 422, "invalid_reason");
        database.transaction(
                db -> {
                    if (action.equals("disable_service") || action.equals("enable_service")) {
                        db.put(
                                "control",
                                "global",
                                "",
                                object().put("disabled", action.equals("disable_service")));
                    } else {
                        check(
                                Set.of("suspend", "resume", "delete").contains(action),
                                422,
                                "invalid_operator_action");
                        ObjectNode value = required(db.get("tenant", tenant, tenant));
                        check(
                                !value.path("state").asText().equals("deleted"),
                                409,
                                "tenant_deleted");
                        String state =
                                switch (action) {
                                    case "resume" -> "active";
                                    case "delete" -> "deleted";
                                    default -> "suspended";
                                };
                        value.put("state", state);
                        db.put("tenant", tenant, tenant, value);
                        if (!action.equals("resume"))
                            for (ObjectNode repo : db.list("repository", tenant))
                                invalidate(
                                        db, tenant, repo.path("id").asText(), 0, "tenant_disabled");
                        if (action.equals("delete"))
                            for (String kind :
                                    List.of("artifact", "preparation", "package", "feedback"))
                                for (ObjectNode doc : db.list(kind, tenant)) {
                                    String docId =
                                            doc.path("id").asText(doc.path("review").asText());
                                    if (!docId.isBlank()) db.delete(kind, docId, tenant);
                                }
                    }
                    audit(db, tenant, operator, action, reason);
                    return null;
                });
    }

    private void maintenance(ServiceDatabase.View db) throws Exception {
        for (String kind : List.of("oauth", "session", "feedback")) {
            for (ObjectNode row : db.list(kind, null)) {
                if (row.path("expires").asLong(Long.MAX_VALUE) <= now() && row.has("id")) {
                    String tenant = row.path("tenant").asText();
                    db.delete(kind, row.path("id").asText(), tenant);
                }
            }
        }
        for (ObjectNode tenant : db.list("tenant", null))
            expireGrants(db, tenant.path("id").asText());
        for (ObjectNode job : db.list("review", null)) {
            String tenant = job.path("tenant").asText(), review = job.path("id").asText();
            if (Set.of("preparing", "running", "publishing").contains(job.path("job").asText())
                    && job.path("leaseUntil").asLong() <= now()) {
                if (job.path("job").asText().equals("publishing")) {
                    job.put("job", "finished")
                            .put("publication", "failed")
                            .put("publicationAfter", now() + Duration.ofMinutes(1).toMillis())
                            .put("publicationAmbiguous", true);
                    db.delete(
                            "publication_lock",
                            tenant
                                    + ":"
                                    + job.path("repository").asText()
                                    + ":"
                                    + job.path("pr").asInt(),
                            tenant);
                } else if (job.path("inferenceStarted").asBoolean()) {
                    job.put("job", "failed")
                            .put("reason", "worker_lost_usage_unknown")
                            .put("metering", "reconciliation_required")
                            .put("reconcileBy", now() + Duration.ofHours(24).toMillis());
                } else if (job.path("latest").asBoolean() && job.path("attempt").asInt() < 3)
                    job.put("job", "queued");
                else {
                    job.put("job", "failed").put("reason", "attempts_exhausted");
                    release(db, job, "scheduler", "attempts_exhausted");
                }
            }
            if (job.path("metering").asText().equals("reconciliation_required")
                    && job.path("reconcileBy").asLong() <= now()) {
                release(db, job, "scheduler", "service_absorbed_reconciliation_timeout");
                audit(db, tenant, "scheduler", "reconciliation_timeout", review);
            }
            if (Set.of("finished", "failed", "cancelled").contains(job.path("job").asText())
                    && now() - job.path("finished").asLong(job.path("created").asLong())
                            >= Duration.ofHours(1).toMillis())
                db.delete("preparation", review, tenant);
            db.put("review", review, tenant, job);
        }
        for (ObjectNode artifact : db.list("artifact", null))
            if (artifact.path("expires").asLong() <= now())
                db.delete(
                        "artifact",
                        artifact.path("review").asText(),
                        artifact.path("tenant").asText());
    }

    public void maintenance() throws Exception {
        database.transaction(
                db -> {
                    maintenance(db);
                    return null;
                });
    }

    /** Signature has already been checked by the HTTP boundary, before JSON parsing. */
    public ObjectNode receive(String delivery, String event, ObjectNode body) throws Exception {
        check(
                delivery != null && delivery.matches("[A-Za-z0-9_-]{1,100}"),
                422,
                "invalid_delivery_id");
        String hash = Json.hash(event + ":" + Json.stringify(body));
        ObjectNode receipt =
                database.transaction(
                        db -> {
                            ObjectNode old = db.get("delivery", delivery, "");
                            if (old != null) {
                                check(
                                        old.path("hash").asText().equals(hash),
                                        409,
                                        "delivery_conflict");
                                return old;
                            }
                            ObjectNode value =
                                    object().put("id", delivery)
                                            .put("hash", hash)
                                            .put("event", event)
                                            .put("state", "received")
                                            .put("created", now());
                            value.set("body", body);
                            db.insert("delivery", delivery, "", value);
                            return value;
                        });
        // Durable receipt is enough to acknowledge; processing can recover after a crash.
        return object().put("delivery", receipt.path("id").asText())
                .put("state", receipt.path("state").asText());
    }

    public void processEvents() throws Exception {
        List<ObjectNode> deliveries =
                database.transaction(
                        db ->
                                db.list("delivery", "").stream()
                                        .filter(
                                                d ->
                                                        d.path("state").asText().equals("received")
                                                                && d.path("retryAfter").asLong()
                                                                        <= now())
                                        .limit(100)
                                        .toList());
        for (ObjectNode delivery : deliveries) {
            String state = "processed", code = "";
            try {
                processEvent(delivery);
            } catch (ServiceException e) {
                if (e.status() >= 500) {
                    retryDelivery(delivery);
                    continue;
                }
                state = "rejected";
                code = e.code();
            } catch (IllegalArgumentException e) {
                state = "rejected";
                code = "invalid_policy";
            } catch (Exception e) {
                retryDelivery(delivery);
                continue;
            }
            String outcome = state, reason = code;
            database.transaction(
                    db -> {
                        ObjectNode current =
                                required(db.get("delivery", delivery.path("id").asText(), ""));
                        current.put("state", outcome).put("reason", reason);
                        current.remove("body");
                        db.put("delivery", current.path("id").asText(), "", current);
                        return null;
                    });
        }
        retryReplenishedReviews();
    }

    private void retryDelivery(ObjectNode delivery) throws Exception {
        database.transaction(
                db -> {
                    ObjectNode current =
                            required(db.get("delivery", delivery.path("id").asText(), ""));
                    int attempts = current.path("attempts").asInt() + 1;
                    current.put("attempts", attempts)
                            .put("retryAfter", now() + Math.min(300000L, 1000L << attempts));
                    if (attempts >= 6) {
                        current.put("state", "dead_letter")
                                .put("reason", "event_processing_exhausted");
                        current.remove("body");
                    }
                    db.put("delivery", current.path("id").asText(), "", current);
                    return null;
                });
    }

    private void scheduleRepository(ObjectNode repository) throws Exception {
        List<Integer> numbers =
                database.transaction(
                        db ->
                                db.list("review", repository.path("tenant").asText()).stream()
                                        .filter(
                                                job ->
                                                        job.path("repository")
                                                                .asText()
                                                                .equals(
                                                                        repository
                                                                                .path("id")
                                                                                .asText()))
                                        .map(job -> job.path("pr").asInt())
                                        .distinct()
                                        .limit(100)
                                        .toList());
        for (int number : numbers) {
            try {
                admit(repository, number, "automatic:current", "github", null, "");
            } catch (ServiceException e) {
                if (e.status() >= 500) throw e;
            }
        }
    }

    private void retryReplenishedReviews() throws Exception {
        List<ObjectNode> blocked =
                database.transaction(
                        db -> {
                            var result = new java.util.ArrayList<ObjectNode>();
                            for (ObjectNode job : db.list("review", null)) {
                                if (job.path("actor").asText().equals("github")
                                        && job.path("latest").asBoolean()
                                        && job.path("reason")
                                                .asText()
                                                .equals("insufficient_credits")
                                        && now() - job.path("created").asLong()
                                                <= Duration.ofDays(1).toMillis()
                                        && available(db, job.path("tenant").asText())
                                                >= settings.maximumReservation()) result.add(job);
                            }
                            return result;
                        });
        for (ObjectNode job : blocked) {
            ObjectNode repository =
                    database.transaction(
                            db ->
                                    required(
                                            db.get(
                                                    "repository",
                                                    job.path("repository").asText(),
                                                    job.path("tenant").asText())));
            if (repository.path("active").asBoolean() && repository.path("enabled").asBoolean())
                admit(repository, job.path("pr").asInt(), "automatic:current", "github", null, "");
        }
    }

    private void processEvent(ObjectNode delivery) throws Exception {
        ObjectNode body = (ObjectNode) delivery.path("body");
        long installation = body.path("installation").path("id").asLong();
        ObjectNode mapping =
                database.transaction(db -> db.get("installation", Long.toString(installation), ""));
        if (mapping == null) return;
        String tenant = mapping.path("tenant").asText(),
                action = body.path("action").asText(),
                event = delivery.path("event").asText();
        if ((event.equals("installation") && Set.of("deleted", "suspend").contains(action))
                || event.equals("installation_repositories")) {
            database.transaction(
                    db -> {
                        if (event.equals("installation")) {
                            mapping.put("active", false);
                            db.put("installation", Long.toString(installation), "", mapping);
                        }
                        for (ObjectNode repo : db.list("repository", tenant)) {
                            boolean removed = event.equals("installation");
                            for (JsonNode r : body.path("repositories_removed"))
                                if (r.path("id").asText().equals(repo.path("id").asText()))
                                    removed = true;
                            if (removed) {
                                repo.put("active", false).put("enabled", false);
                                db.put("repository", repo.path("id").asText(), tenant, repo);
                                invalidate(
                                        db,
                                        tenant,
                                        repo.path("id").asText(),
                                        0,
                                        "installation_revoked");
                            }
                        }
                        audit(db, tenant, "github", "installation_lifecycle", action);
                        return null;
                    });
            return;
        }
        check(mapping.path("active").asBoolean(), 403, "installation_revoked");
        String repoId = body.path("repository").path("id").asText();
        ObjectNode repo = database.transaction(db -> db.get("repository", repoId, tenant));
        if (repo == null || repo.path("installation").asLong() != installation) return;
        if (event.equals("repository")
                && Set.of("transferred", "renamed", "deleted").contains(action)) {
            database.transaction(
                    db -> {
                        repo.put("active", false).put("enabled", false);
                        db.put("repository", repoId, tenant, repo);
                        invalidate(db, tenant, repoId, 0, "repository_changed");
                        return null;
                    });
            return;
        }
        if (event.equals("push")) {
            boolean target = false;
            for (JsonNode branch : repo.path("branches"))
                if (body.path("ref").asText().equals("refs/heads/" + branch.asText()))
                    target = true;
            if (!target) return;
            database.transaction(
                    db -> {
                        invalidate(db, tenant, repoId, 0, "target_changed");
                        return null;
                    });
            if (repo.path("enabled").asBoolean()) scheduleRepository(repo);
            return;
        }
        if (!event.equals("pull_request")) return;
        int pr = body.path("number").asInt();
        if (Set.of("closed", "converted_to_draft").contains(action)) {
            ObjectNode current = liveSnapshot(repo, pr);
            if (current.path("open").asBoolean() && !current.path("draft").asBoolean()) return;
            database.transaction(
                    db -> {
                        invalidate(db, tenant, repoId, pr, "pr_ineligible");
                        return null;
                    });
            return;
        }
        if (Set.of("opened", "synchronize", "reopened", "ready_for_review").contains(action)
                && repo.path("enabled").asBoolean()) {
            // Automatic identities coalesce across duplicate/reordered deliveries for the same live
            // snapshot.
            admit(repo, pr, "automatic:current", "github", null, "");
        }
    }
}
