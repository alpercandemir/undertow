# Major dependency upgrade: Spring Web 5 to 6

This demo shows a real API incompatibility and its correction when `org.springframework:spring-web` changes from **5.3.31 to 6.0.7**. These historical versions are demonstration inputs, not recommendations for new applications.

In Spring 5, `ResponseEntity.getStatusCode()` returns `HttpStatus`. In Spring 6, its declared return type is `HttpStatusCode`. Changing only the POM breaks the selected caller:

```diff
- import org.springframework.http.HttpStatus;
+ import org.springframework.http.HttpStatusCode;

- HttpStatus status = response.getStatusCode();
+ HttpStatusCode status = response.getStatusCode();
```

Version-pinned official sources: [5.3.31 ResponseEntity](https://github.com/spring-projects/spring-framework/blob/v5.3.31/spring-web/src/main/java/org/springframework/http/ResponseEntity.java) and [6.0.7 ResponseEntity](https://github.com/spring-projects/spring-framework/blob/v6.0.7/spring-web/src/main/java/org/springframework/http/ResponseEntity.java).

## Compile and check observable behavior

Run from the project root. Python 3, Git, and JDK 17+ are required for the sample; it has been checked with JDK 21. The local Maven repository must contain `spring-web`, `spring-core`, and `spring-jcl` JARs for both versions. The script stops with the missing artifact path when prerequisites are absent. Override locations with `--maven-repo` and `--java-home`.

```sh
python3 demo/dependency-upgrade/run.py --output .undertow/spring-major-demo
```

The script creates three commits in the new output directory's `repo/.git`, tagged `base`, `upgrade`, and `fixed`. It does not change the parent project's history. Each check reads the source from its exact commit.

| Tag | Version | Expected outcome |
|---|---|---|
| `base` | 5.3.31 | Compilation and 200/503 status checks pass |
| `upgrade` | 6.0.7 | The unchanged caller fails compilation with the incompatible status-code return type |
| `fixed` | 6.0.7 | Compilation and 200/503 checks pass after correcting the type |

`verification.json` records commits, compiler, commands, JAR SHA-256 digests, and exit codes. `checks/` retains source and compile/execution logs. `upgrade.diff` contains the POM change; `fix.diff` contains the source correction. These are direct `javac`/`java` checks with selected JARs, not full Maven transitive dependency resolution.

## Investigate the upgrade with Undertow

The harness requires JDK 25 and a built `target/undertow.jar`. This separate step uses network access, no model key, and no replay:

```sh
java -jar target/undertow.jar investigate-dependency \
  --repo .undertow/spring-major-demo/repo --base base --head upgrade \
  --coordinate org.springframework:spring-web \
  --old-version 5.3.31 --new-version 6.0.7 \
  --output .undertow/spring-major-demo/investigation.json
```

The demo registers official, version-pinned `ResponseEntity.java` sources and release notes in its trusted base commit. It does not change the parent project's source registry. Undertow collects:

- `compare_dependencies`: the direct POM version change between commits.
- `fetch_migration_evidence`: registered version-specific official documents/source, content hashes, and retrieval failures.
- `query_vulnerabilities`: OSV records for the target coordinate/version. A match is not proof of exploitability in this application.

Inspect `failures` and `source_failures`. Retrieval failures must not become an assertion that no advisories exist. Even successful retrieval produces `status: partial`: the command does not verify every intermediate version, API change, or runtime behavior.

## What the evidence establishes

The demo script executes the compiler and corrected behavior checks. Undertow's dependency command collects supporting evidence; it does not automatically produce a semantic finding or repair code. These local checks are not trusted imported CI evidence.

`JAVA-DEPENDENCY-001` requires migration claims to use retrieved version-specific evidence. Optional model investigation uses the separate `review --mode tools` flow; this demo makes no live model call. The small correction addresses the demonstrated return-type incompatibility, not migration of an entire application to Spring 6. A compiler failure is compatibility evidence here and must not be mislabeled as a reproduced behavioral assertion failure.
