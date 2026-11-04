#!/usr/bin/env python3
"""Authorize from runner context and live permission metadata, never PR text."""

import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path


def eligible(event, event_name, repository, opted_in):
    if event_name == "workflow_dispatch":
        number = str(event.get("inputs", {}).get("pr_number", ""))
        return bool(re.fullmatch(r"[1-9][0-9]{0,7}", number))
    pr = event.get("pull_request", {})
    return (event_name == "pull_request_target" and opted_in == "true"
            and event.get("action") in {"opened", "synchronize", "ready_for_review", "reopened"}
            and pr.get("head", {}).get("repo", {}).get("full_name") == repository
            and pr.get("base", {}).get("ref") == event.get("repository", {}).get("default_branch")
            and not pr.get("draft", True) and pr.get("state") == "open")


def permission(repository, login):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", login):
        return False
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/collaborators/{login}/permission",
        headers={"Authorization": "Bearer " + os.environ.get("GITHUB_TOKEN", ""),
                 "Accept": "application/vnd.github+json", "User-Agent": "undertow-trigger"})
    # Do not forward authorization through redirects.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
            result = json.loads(response.read(100_001))
    except (urllib.error.URLError, ValueError):
        raise RuntimeError("Unable to verify trusted runner permissions") from None
    return result.get("permission") in {"write", "admin", "maintain"}


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    repository = os.environ["GITHUB_REPOSITORY"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid runner repository")
    name = os.environ["GITHUB_EVENT_NAME"]
    allowed = eligible(event, name, repository, os.environ.get("UNDERTOW_AUTO_REVIEW", "false"))
    if allowed:
        login = os.environ["GITHUB_ACTOR"] if name == "workflow_dispatch" else event["pull_request"]["user"]["login"]
        allowed = permission(repository, login)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write("authorized=" + str(allowed).lower() + "\n")
    print("Trigger authorized" if allowed else "Trigger skipped by trusted contribution/draft/maintainer policy")


if __name__ == "__main__":
    main()
