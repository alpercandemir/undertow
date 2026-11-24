import json
import os
from pathlib import Path
import sys
from unittest import TestCase
from unittest.mock import call, patch

from fixtures import ACTION, POLICY, SHORT_POLICY, GitReviewTest

sys.path.insert(0, str(ACTION))
import github_review


REPOSITORY = "example/fixture"
PR = 17
BASE = "1" * 40
HEAD = "2" * 40
TOKEN = "fixture"
COMMENTS = f"/repos/{REPOSITORY}/issues/{PR}/comments"
PULL = f"/repos/{REPOSITORY}/pulls/{PR}"
MARKER = github_review.MARKER


def open_pr(base, head):
    return {"base": {"sha": base}, "head": {"sha": head}, "state": "open"}


class CommentTests(TestCase):
    def test_first_run_posts_and_next_run_patches_the_bot_comment(self):
        summary = "Review summary"
        current = open_pr(BASE, HEAD)
        unrelated = {"id": 16, "user": {"login": "contributor"}, "body": f"{MARKER}\nSpoofed marker"}
        existing = {"id": 56, "user": {"login": "github-actions[bot]"}, "body": f"{MARKER}\nFirst report"}
        list_comments = call("GET", f"{COMMENTS}?per_page=50&page=1", TOKEN)
        get_pr = call("GET", PULL, TOKEN)
        payload = {"body": f"{MARKER}\n{summary}"}
        with patch.object(github_review, "github", side_effect=[
            [unrelated], current, {}, [unrelated, existing], current, {},
        ]) as github:
            for _ in range(2):
                github_review.update_comment(REPOSITORY, PR, BASE, HEAD, TOKEN, summary)
        self.assertEqual(github.call_args_list, [
            list_comments, get_pr, call("POST", COMMENTS, TOKEN, payload),
            list_comments, get_pr, call("PATCH", f"/repos/{REPOSITORY}/issues/comments/56", TOKEN, payload),
        ])

    def test_stale_or_closed_pull_request_cannot_publish(self):
        for current in (open_pr(BASE, "3" * 40), open_pr("3" * 40, HEAD),
                        {**open_pr(BASE, HEAD), "state": "closed"}):
            with (
                self.subTest(current=current),
                patch.object(github_review, "github", side_effect=[[], current]) as github,
            ):
                with self.assertRaisesRegex(github_review.ActionError, "pull request changed"):
                    github_review.update_comment(REPOSITORY, PR, BASE, HEAD, TOKEN, "Review")
                self.assertEqual([item.args[0] for item in github.call_args_list], ["GET", "GET"])

    def test_empty_report_only_removes_the_previous_owned_comment(self):
        previous = {"id": 56, "user": {"login": "github-actions[bot]"}, "body": f"{MARKER}\nOld report"}
        unrelated = {"id": 57, "user": {"login": "contributor"}, "body": f"{MARKER}\nDiscussion"}
        for entries, expected in (([unrelated], ["GET", "GET"]), ([previous], ["GET", "GET", "DELETE"])):
            with self.subTest(entries=entries), patch.object(github_review, "github", side_effect=[
                entries, open_pr(BASE, HEAD), None,
            ]) as github:
                github_review.update_comment(REPOSITORY, PR, BASE, HEAD, TOKEN, "")
            self.assertEqual([item.args[0] for item in github.call_args_list], expected)


class ActionTest(GitReviewTest):
    def test_action_analyzes_git_objects_without_running_candidate_code(self):
        sentinel = self.directory / "candidate-ran"
        candidate = f"from pathlib import Path\nPath({str(sentinel)!r}).touch()\n"
        self.comparison({"policy.py": POLICY}, {"policy.py": candidate + SHORT_POLICY})
        event_file = self.directory / "event.json"
        event_file.write_text(json.dumps({
            "number": PR, "pull_request": open_pr(self.base, self.head),
        }), encoding="utf-8")
        outputs = self.directory / "outputs"
        summary = self.directory / "summary"
        env = {
            **self.env, "RUNNER_TEMP": str(self.directory), "GITHUB_REPOSITORY": REPOSITORY,
            "GITHUB_SERVER_URL": "https://github.com", "UNDERTOW_GITHUB_TOKEN": TOKEN,
            "GITHUB_EVENT_PATH": str(event_file), "GITHUB_EVENT_NAME": "pull_request_target",
            "GITHUB_OUTPUT": str(outputs), "GITHUB_STEP_SUMMARY": str(summary),
            "UNDERTOW_COMMENT": "true",
        }
        fetch_from_git = github_review.fetch

        def fetch_local(repo, root, token, base, head, number):
            self.assertEqual((root, token, base, head, number),
                             (f"https://github.com/{REPOSITORY}", TOKEN, self.base, self.head, PR))
            fetch_from_git(repo, str(self.bare), token, base, head, number)
            self.assertEqual(self.git("rev-parse", "--is-bare-repository", repo=repo), "true")
            self.assertFalse((Path(repo) / "policy.py").exists())

        with (
            patch.dict(os.environ, env, clear=True),
            patch.object(github_review, "fetch", side_effect=fetch_local) as fetch,
            patch.object(github_review, "github", side_effect=[
                [], open_pr(self.base, self.head), {},
            ]) as github,
        ):
            github_review.main()
        fetch.assert_called_once()
        self.assertFalse(sentinel.exists())
        self.assertEqual([item.args[0] for item in github.call_args_list], ["GET", "GET", "POST"])
        values = dict(line.split("=", 1) for line in outputs.read_text().splitlines())
        self.assertEqual((values["status"], values["base"], values["head"], values["comment"]),
                         ("findings", self.base, self.head, "published"))
        report = json.loads((Path(values["directory"]) / "report.json").read_text())
        finding, = report["findings"]
        self.assertIn("age_days = 30", finding["scenario"])
        self.assertEqual((report["schema_version"], finding["evidence_level"]), (1, "source_inferred"))
        rendered = (Path(values["directory"]) / "summary.md").read_text()
        self.assertEqual(github.call_args.args, ("POST", COMMENTS, TOKEN, {"body": f"{MARKER}\n{rendered}"}))
        self.assertEqual(summary.read_text(), rendered + "\n")
        self.assertIn("**Review question**", rendered)

    def test_action_failure_retains_the_report_schema_without_publishing(self):
        outputs = self.directory / "outputs"
        summary = self.directory / "summary"
        env = {
            **self.env, "RUNNER_TEMP": str(self.directory), "GITHUB_REPOSITORY": "invalid",
            "GITHUB_OUTPUT": str(outputs), "GITHUB_STEP_SUMMARY": str(summary), "UNDERTOW_COMMENT": "true",
        }
        with patch.dict(os.environ, env, clear=True), patch.object(github_review, "github") as github:
            github_review.main()
        github.assert_not_called()
        values = dict(line.split("=", 1) for line in outputs.read_text().splitlines())
        self.assertEqual((values["status"], values["base"], values["head"], values["comment"]),
                         ("unavailable", "", "", "unavailable"))
        report = json.loads((Path(values["directory"]) / "report.json").read_text())
        self.assertEqual((report["schema_version"], report["status"], report["findings"], report["evidence"]),
                         (1, "unavailable", [], {}))
        for limitation in report["analysis_limitations"]:
            self.assertIn(limitation, summary.read_text())
