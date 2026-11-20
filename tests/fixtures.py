"""Build isolated, disposable Git histories for the source review worker."""

import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


ACTION = Path(__file__).resolve().parents[1] / "actions" / "change-review"
SOURCE_URL = "https://github.com/example/fixture"

POLICY = "def can_return(age_days):\n    return age_days <= 30\n"
SHORT_POLICY = POLICY.replace("30", "14")


def local_environment():
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("GIT_") and key not in {"GEMINI_API_KEY", "UNDERTOW_GEMINI_MODEL"}}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0")
    return env


class GitReviewTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(self.enterContext(TemporaryDirectory()))
        self.worktree = self.directory / "worktree"
        self.bare = self.directory / "source.git"
        self.output = self.directory / "report"
        self.env = local_environment()
        self.env.update(
            GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
            GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid",
            GIT_AUTHOR_DATE="2000-01-01T00:00:00+00:00", GIT_COMMITTER_DATE="2000-01-01T00:00:00+00:00",
            GIT_ATTR_NOSYSTEM="1",
        )
        self.git("init", "--quiet", "--template=", "--initial-branch=main",
                 "--object-format=sha1", str(self.worktree), repo=self.directory)

    def git(self, *args, repo=None):
        return subprocess.check_output(
            ["git", "-C", str(repo or self.worktree), *args],
            env=self.env, text=True, timeout=20,
        ).strip()

    def commit(self, changes, message):
        for name, content in changes.items():
            path = self.worktree / name
            if content is None:
                path.unlink()
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
        self.git("add", "--all")
        self.git("commit", "--quiet", "-m", message)
        return self.git("rev-parse", "HEAD")

    def comparison(self, before, after):
        self.base = self.commit(before, "Base")
        self.head = self.commit(after, "Head")
        self.git("clone", "--quiet", "--bare", "--template=", str(self.worktree), str(self.bare))

    def engine_args(self):
        return [
            "--repo", str(self.bare), "--base", self.base, "--head", self.head,
            "--merge-base", "--output", str(self.output), "--source-url", SOURCE_URL,
        ]

    def read_report(self):
        return json.loads((self.output / "report.json").read_text(encoding="utf-8"))

    def local_report(self, before, after, expected_status=None):
        self.comparison(before, after)
        subprocess.run(
            [sys.executable, "-I", str(ACTION / "review_engine.py"), *self.engine_args()],
            env=self.env, check=True, timeout=20,
        )
        report = self.read_report()
        self.assertEqual(report["mode"], "local")
        self.assertEqual((report["base"], report["head"]), (self.base, self.head))
        if expected_status:
            self.assertEqual(report["status"], expected_status, report["notes"])
        return report
