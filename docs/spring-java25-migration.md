# Spring Boot 4 and Java 25 migration

The harness now compiles for Java 25 and runs as a non-web Spring Boot 4.1.1
application. The existing picocli command names, options, report formats and exit
codes remain the command-line contract. `target/undertow.jar` is now a Boot
executable archive with nested dependencies rather than a shaded archive.

## Application boundaries

`UndertowApplication` starts and closes the application context around command
execution. Explicit beans provide a stateless model-client factory and a prototype
command tree. Each invocation receives fresh mutable option fields; clients are
created inside the command after loading the pinned review policy and closed by
the command. The review engine, tools, validation and publisher remain plain Java.

Spring configuration is restricted to bundled `undertow.properties`. A bootstrap
property source overrides Boot's default working-directory configuration search.
Raw CLI arguments go only to picocli. No reviewed source is scanned for components,
and review policy still comes from base-commit Git objects. Help, version and
collector commands do not need a model credential. No web starter is installed.

## Dependency and toolchain decisions

- The [Spring Boot 4.1.1 release](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.1)
  supplies dependency and standard Maven plugin management. Its
  [system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
  include Java 25.
- Jackson 2 stays at the strict JSON/YAML and schema-validator boundaries. Boot
  manages its version (2.21.5 in this release), alongside JUnit Jupiter 6.0.3 and
  AssertJ 3.27.7. The
  [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
  documents Jackson 2 management for applications whose libraries still require
  it. A Jackson 3 migration would also require replacing the schema-validator
  integration; that is a separate change. No Spring JSON mapper replaces the
  harness's explicitly constrained mappers.
- [Spotless 3.10.3](https://github.com/diffplug/spotless/releases/tag/maven%2F3.10.3)
  with [google-java-format 1.36.1](https://github.com/google/google-java-format/releases/tag/v1.36.1)
  retains four-space AOSP formatting.
  [SpotBugs plugin 4.10.4.1](https://github.com/spotbugs/spotbugs-maven-plugin/releases/tag/spotbugs-maven-plugin-4.10.4.1)
  analyzes Java 25 bytecode. No static-analysis suppression was added.
- CI, the reusable action and the review/publish workflow use Java 25 for the
  harness. CI switches to Java 21 for the unchanged demo and back to Java 25 for
  packaged CLI evaluation. Actions remain pinned to their existing commit SHAs.

The demo and labeled fixtures retain their existing content and toolchains.
The supported syntax-analysis target remains Java 21; changing the harness runtime
does not establish support for reviewing new Java 25 syntax. Live-model accuracy
and complete dependency migration coverage remain unmeasured.

## Validation

On 30 September 2026, using the installed JDK 25:

- `mvn --offline spotless:apply verify` passed all 55 Java tests, formatting and
  SpotBugs at the High threshold, with zero reported bugs or analysis errors.
  CLI tests cover help/version/usage exit codes, fresh command state,
  credential-free collection and rejection of working-directory Spring
  configuration. Workflow tests check the harness/demo toolchain separation.
- All 40 authored fixtures completed in collect, diff and tools modes: 120
  reports, zero failed reports. Diff/tool replay preserved all expected findings
  with no additional findings. Results are in
  `.undertow/spring-java25-evaluation/evaluation.json`. This validates harness
  behavior rather than live-model accuracy.
- Seven Python workflow-helper tests passed. `git diff --check` passed;
  `demo/` and `evals/` have no changes.
- The final executable JAR reports Boot 4.1.1 and contains Java 25 bytecode
  (class-file major version 69). Nine packaged CLI smoke checks passed from a
  directory containing malicious root and `config/` Spring property files,
  including no-argument usage, command help, version and invalid-option exits.

The current Spotless release still emits a Java 25 warning about its internal use
of `sun.misc.Unsafe`; formatting completes successfully. Demo Docker integration
tests, live model requests and publication were not run for this migration.
