"""Observable publication guardrails; fixtures are intentionally synthetic."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "public_check", Path(__file__).resolve().parents[1] / "public-check.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class PublicContentCheckTest(unittest.TestCase):
    def test_environment_examples_are_allowed_but_credentials_are_not(self):
        self.assertEqual(CHECK.path_issues(".env.example"), [])
        for name in (".env", ".env.production", "app.pem", "id_rsa", "service.local.json"):
            self.assertIn("credential_or_runtime_file", CHECK.path_issues(name))

    def test_customer_policy_is_allowed_but_local_artifacts_are_not(self):
        self.assertEqual(CHECK.path_issues("demo/customer-service/.undertow/rules.yaml"), [])
        self.assertIn("local_review_artifact", CHECK.path_issues(".undertow/report.json"))

    def test_workstation_paths_and_turkish_text_are_rejected(self):
        workstation = "/" + "Users" + "/synthetic-person/review/report.md"
        self.assertIn("personal_workstation_path", CHECK.content_issues(workstation.encode(), []))
        self.assertIn("turkish_text", CHECK.content_issues("\u0130nceleme".encode(), []))

    def test_exact_inherited_credentials_are_detected_without_returning_values(self):
        fixture = b"synthetic-test-credential-value"
        self.assertEqual(CHECK.content_issues(fixture, [fixture]), ["exact_environment_credential"])

    def test_english_examples_and_placeholder_configuration_are_allowed(self):
        example = b"GEMINI_API_KEY: REPLACE_WITH_YOUR_KEY; API errors remain visible."
        self.assertEqual(CHECK.content_issues(example, []), [])

    def test_binary_files_are_still_checked_for_exact_credentials(self):
        fixture = b"synthetic-test-credential-value"
        self.assertIn("exact_environment_credential", CHECK.content_issues(b"\xff" + fixture, [fixture]))


if __name__ == "__main__":
    unittest.main()
