"""Exercise repository-only discovery and paired Maven verdicts."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
REVIEW = ROOT / "scripts/change-review.py"


class ChangeReviewTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Undertow Test")
        self.git("config", "user.email", "undertow@example.invalid")
        pom = (ROOT / "demo/refund-extraction/pom.xml").read_text().replace(
            "<artifactId>refund-extraction</artifactId>", "<artifactId>allowance</artifactId>")
        self.put("pom.xml", pom)
        self.source = "src/main/java/example/allowance/Allowance.java"
        self.put(self.source, self.implementation("limit - used"))
        self.put("src/test/java/example/allowance/AllowanceTest.java", """package example.allowance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AllowanceTest {
    @Test
    void calculatesRemainingAllowance() {
        assertEquals(1, Allowance.remaining(3, 2));
    }
}
""")
        self.baseline = self.commit("Introduce allowance tracking")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def put(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def implementation(self, expression):
        return """package example.allowance;

public final class Allowance {
    /** Reports how much allowance the caller can still use. */
    public static int remaining(int limit, int used) {
        return subtractUsed(limit, used);
    }

    private static int subtractUsed(int limit, int used) {
        return EXPRESSION;
    }
}
""".replace("EXPRESSION", expression)

    def run_comparison(self, head, *options):
        output = self.root / "evidence"
        run = subprocess.run(
            [sys.executable, str(REVIEW), "--repo", str(self.repo), "--base", self.baseline,
             "--head", head, "--output", str(output), *options],
            capture_output=True, text=True, timeout=180,
        )
        self.assertTrue((output / "report.json").exists(), run.stderr)
        return run, json.loads((output / "report.json").read_text()), output

    def test_replays_an_existing_expectation_from_pinned_commits(self):
        self.put(self.source, self.implementation("limit - used + 1"))
        candidate = self.commit("Update remaining allowance")
        self.put(self.source, self.implementation("100"))
        run, report, output = self.run_comparison(candidate)

        self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
        self.assertEqual(report["head"], candidate)
        self.assertEqual(report["status"], "reproduced_breakage")
        finding, = report["findings"]
        self.assertEqual(finding["before"], "number:1")
        self.assertEqual(finding["after"], "number:2")
        self.assertEqual(finding["route"], [
            "example.allowance.Allowance.remaining", "example.allowance.Allowance.subtractUsed",
        ])
        self.assertTrue(all(attempt["validated"] for attempt in finding["replay"]))
        self.assertTrue((output / finding["export"]).is_file())

    def test_compilation_failure_is_incomplete(self):
        self.put(self.source, self.implementation('"cannot compile"'))
        candidate = self.commit("Change allowance computation")
        run, report, _ = self.run_comparison(candidate)

        self.assertEqual(run.returncode, 2, run.stdout + run.stderr)
        self.assertEqual(report["status"], "incomplete_comparison")
        self.assertEqual(report["findings"], [])
        self.assertEqual(report["scenarios"][0]["status"], "incomplete_comparison")

    def test_replays_an_unchanged_stateful_test_as_the_regression(self):
        self.put(self.source, self.implementation("limit - used").replace(
            "public static int remaining", "public int remaining"))
        self.put("src/test/java/example/allowance/AllowanceTest.java", """package example.allowance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class AllowanceTest {
    @Test
    void calculatesRemainingAllowance() {
        var allowance = new Allowance();
        int used = 2;
        int remaining = allowance.remaining(3, used);
        assertEquals(1, remaining, "Tracks remaining allowance");
    }
}
""")
        self.baseline = self.commit("Use the existing usage fixture")
        self.put(self.source, self.implementation("limit - used + 1").replace(
            "public static int remaining", "public int remaining"))
        candidate = self.commit("Change allowance computation")
        run, report, _ = self.run_comparison(candidate)

        self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
        self.assertEqual(report["scenarios"], [])
        finding, = report["findings"]
        self.assertEqual(finding["kind"], "reproduced_breakage")
        self.assertEqual(finding["test"], "example.allowance.AllowanceTest#calculatesRemainingAllowance")
        self.assertIn("Tracks remaining allowance", finding["assertion_message"])
        self.assertTrue(all(attempt["validated"] for attempt in finding["replay"]))
        self.assertNotIn("export", finding)

    def test_documentation_only_change_does_not_build_project(self):
        self.put("README.md", "# Allowance tracking\n")
        candidate = self.commit("Document the project")
        run, report, _ = self.run_comparison(candidate)

        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(report["status"], "no_executable_change_found")
        self.assertEqual(report["execution"], {})

    @unittest.skipUnless(os.environ.get("UNDERTOW_TEST_ANALYSIS_JDK"), "requires two configured JDKs")
    def test_legacy_project_uses_its_own_jdk_and_maven_wrapper(self):
        java_version = os.environ["UNDERTOW_TEST_BUILD_MAJOR"]
        specification = "1.8" if java_version == "8" else java_version
        pom = (self.repo / "pom.xml").read_text().replace(
            "<maven.compiler.release>21", "<maven.compiler.release>8").replace(
            "<groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>6.1.0</version>",
            "<groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version>")
        self.put("pom.xml", pom)
        self.put("mvnw", '#!/bin/sh\nexec mvn "$@"\n')
        (self.repo / "mvnw").chmod(0o755)
        self.put("src/test/java/example/allowance/AllowanceTest.java", f"""package example.allowance;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class AllowanceTest {{
    @Test
    public void calculatesRemainingAllowance() {{
        assertEquals("{specification}", System.getProperty("java.specification.version"));
        assertEquals(1, Allowance.remaining(3, 2));
    }}
}}
""")
        self.baseline = self.commit("Support a legacy build JDK")
        self.put(self.source, self.implementation("limit - used + 1"))
        candidate = self.commit("Change allowance computation")
        run, report, output = self.run_comparison(
            candidate, "--analysis-java-home", os.environ["UNDERTOW_TEST_ANALYSIS_JDK"],
            "--build-java-home", os.environ["JAVA_HOME"],
        )

        self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
        self.assertIn(f'version "{specification}', report["java"]["build"])
        self.assertIn('version "25', report["java"]["analysis"])
        self.assertEqual(report["execution"]["existing_tests"]["base"]["command"][0], "./mvnw")
        self.assertEqual(report["execution"]["existing_tests"]["base"]["exit_code"], 0)
        finding, = report["findings"]
        self.assertTrue((output / finding["export"]).is_file())
