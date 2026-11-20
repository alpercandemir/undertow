"""Run an advisory GitHub review and optionally update one PR comment."""

import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MARKER = "<!-- undertow-business-change-review -->"
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
ENGINE = Path(__file__).resolve().with_name("review_engine.py")


class ActionError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def github_url(name):
    value = os.environ[name].rstrip("/")
    url = urlsplit(value)
    if (url.scheme != "https" or not re.fullmatch(r"[A-Za-z0-9.-]+(?::[0-9]+)?", url.netloc)
            or url.query or url.fragment or url.path not in {"", "/api/v3"}):
        raise ActionError("GitHub did not provide a valid HTTPS endpoint.")
    return value


def github(method, path, token, payload=None):
    request = Request(
        github_url("GITHUB_API_URL") + path,
        method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "Undertow-Change-Review",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=12) as response:
            return json.loads(response.read(4 * 1024 * 1024))
    except HTTPError as error:
        raise ActionError(f"GitHub returned HTTP {error.code}.") from error
    except (URLError, OSError, ValueError) as error:
        raise ActionError("GitHub did not return a usable API response.") from error


def event_comparison():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    pr = event.get("pull_request")
    explicit_base = os.environ.get("UNDERTOW_BASE", "")
    explicit_head = os.environ.get("UNDERTOW_HEAD", "")
    number = None
    if pr and os.environ.get("GITHUB_EVENT_NAME") in {"pull_request", "pull_request_target"}:
        pr_base, pr_head = pr["base"]["sha"], pr["head"]["sha"]
        if (not explicit_base and not explicit_head) or (explicit_base, explicit_head) == (pr_base, pr_head):
            number = event["number"]
            if type(number) is not int or number < 1:
                raise ActionError("GitHub did not provide a valid pull request number.")
    if explicit_base or explicit_head:
        base, head = explicit_base, explicit_head
    elif number:
        base, head = pr_base, pr_head
    elif os.environ.get("GITHUB_EVENT_NAME") == "push":
        base, head = event.get("before", ""), event.get("after", "")
    else:
        raise ActionError("Use a pull request or push event, or provide both full commit SHAs.")
    if not all(isinstance(sha, str) and SHA.fullmatch(sha) and sha != "0" * 40 for sha in (base, head)):
        raise ActionError("Two full, non-empty GitHub commit SHAs are required.")
    return base, head, number


def fetch(repo, root, token, base, head, number):
    env = {name: value for name, value in os.environ.items()
           if not name.startswith("GIT_") and name not in {"GEMINI_API_KEY", "UNDERTOW_GITHUB_TOKEN"}}
    auth = base64.b64encode(f"x-access-token:{token}".encode()).decode()
    env.update(
        GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT="0",
        GIT_CONFIG_COUNT="2",
        GIT_CONFIG_KEY_0=f"http.{root}/.extraHeader", GIT_CONFIG_VALUE_0=f"Authorization: Basic {auth}",
        GIT_CONFIG_KEY_1="http.followRedirects", GIT_CONFIG_VALUE_1="false",
    )
    git = ["git", "-C", str(repo)]
    options = {"env": env, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL, "timeout": 60}
    subprocess.run([*git, "init", "--bare", "--template="], check=True, **options)
    command = [*git, "fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", root]
    if subprocess.run([*command, base, head], **options).returncode:
        if number is None:
            raise ActionError("The requested Git commits could not be fetched.")
        # A fork commit can also be fetched through GitHub's PR ref. The engine still uses the event SHA.
        if subprocess.run([*command, base, f"refs/pull/{number}/head"], **options).returncode:
            raise ActionError("The pull request's Git commits could not be fetched.")


def update_comment(repository, number, base, head, token, summary):
    api_repo = f"/repos/{repository}"
    comments = f"{api_repo}/issues/{number}/comments"
    comment_id = None
    for page in range(1, 11):
        entries = github("GET", f"{comments}?per_page=50&page={page}", token)
        for entry in entries:
            if (entry["user"]["login"] == "github-actions[bot]"
                    and str(entry["body"]).startswith(MARKER + "\n")):
                comment_id = entry["id"]
                break
        if comment_id or len(entries) < 50:
            break
    else:
        raise ActionError("The existing Undertow comment could not be located within the page limit.")

    current = github("GET", f"{api_repo}/pulls/{number}", token)
    if current["base"]["sha"] != base or current["head"]["sha"] != head or current["state"] != "open":
        raise ActionError("The pull request changed while this report was being prepared.")
    body = {"body": f"{MARKER}\n{summary}"}
    if comment_id:
        github("PATCH", f"{api_repo}/issues/comments/{int(comment_id)}", token, body)
    else:
        github("POST", comments, token, body)


def append(variable, value):
    with Path(os.environ[variable]).open("a", encoding="utf-8") as output:
        output.write(value)


def main():
    output = Path(tempfile.mkdtemp(prefix="undertow-", dir=os.environ["RUNNER_TEMP"]))
    append("GITHUB_OUTPUT", f"directory={output}\n")
    summary_path = output / "summary.md"
    report_path = output / "report.json"
    try:
        repository = os.environ["GITHUB_REPOSITORY"]
        if not REPOSITORY.fullmatch(repository):
            raise ActionError("GitHub did not provide a valid repository name.")
        root = f"{github_url('GITHUB_SERVER_URL')}/{repository}"
        token = os.environ["UNDERTOW_GITHUB_TOKEN"]
        base, head, number = event_comparison()
        with tempfile.TemporaryDirectory(prefix="undertow-git-", dir=os.environ["RUNNER_TEMP"]) as repo:
            fetch(repo, root, token, base, head, number)
            env = {name: value for name, value in os.environ.items()
                   if not name.startswith("GIT_") and name != "UNDERTOW_GITHUB_TOKEN"}
            command = [
                sys.executable, "-I", str(ENGINE), "--repo", repo, "--base", base, "--head", head,
                "--output", str(output), "--source-url", root,
            ]
            if number:
                command.append("--merge-base")
            subprocess.run(command, check=True, env=env, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=120)
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (ActionError, subprocess.SubprocessError, OSError, KeyError, TypeError, ValueError) as error:
        note = str(error) if isinstance(error, ActionError) else "Undertow could not finish this run."
        report = {
            "version": None, "status": "unavailable", "mode": None,
            "base": None, "head": None, "coverage": {}, "findings": [], "evidence": {}, "notes": [note],
        }
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        summary_path.write_text(f"## Undertow business change report\n\n**Analysis unavailable.** {note}\n",
                                encoding="utf-8")
    summary = summary_path.read_text(encoding="utf-8")
    publish = "disabled"
    if os.environ.get("UNDERTOW_COMMENT") == "true":
        if report["status"] == "unavailable":
            publish = "unavailable"
        elif number is None:
            publish = "skipped"
        else:
            try:
                update_comment(repository, number, base, head, token, summary)
                publish = "published"
            except (ActionError, KeyError, TypeError, ValueError) as error:
                note = str(error) if isinstance(error, ActionError) else "GitHub returned an unexpected response."
                summary += f"\n**PR comment unavailable.** {note}\n"
                publish = "unavailable"
    append("GITHUB_STEP_SUMMARY", summary + "\n")
    append("GITHUB_OUTPUT", f"status={report['status']}\nbase={report.get('base') or ''}\n"
                            f"head={report.get('head') or ''}\ncomment={publish}\n")


if __name__ == "__main__":
    main()
