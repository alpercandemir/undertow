#!/usr/bin/env python3
"""Trusted runner: verify GitHub run/artifact origin, then import JUnit/SpotBugs/tree data.

The receipt must stay outside PR-controlled input. API provenance verifies origin;
it does not prove that tests cover all behavior or that analyzer output is correct.
"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

spec = importlib.util.spec_from_file_location("ci_formats", Path(__file__).with_name("ci-formats.py"))
formats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formats)
MAX_ARCHIVE = 32_000_000


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def get(url, token, limit=MAX_ARCHIVE):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.username or parsed.port:
        raise ValueError("Invalid CI artifact host")
    if parsed.hostname != "api.github.com" and not (parsed.hostname.endswith(".actions.githubusercontent.com")
                                                    or parsed.hostname.endswith(".blob.core.windows.net")):
        raise ValueError("Untrusted CI artifact host")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "undertow-ci-import"}
    if parsed.hostname == "api.github.com":
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, headers=headers)
    try:
        response = urllib.request.build_opener(NoRedirect).open(request, timeout=30)
    except urllib.error.HTTPError as error:
        if error.code == 302 and parsed.hostname == "api.github.com":
            return get(error.headers["Location"], "", limit)
        raise RuntimeError("CI API request failed: HTTP " + str(error.code)) from None
    with response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("CI artifact exceeds size limit")
    return data


def verify_origin(run, artifact, repository, workflow, head):
    if (run.get("repository", {}).get("full_name") != repository
            or str(run.get("workflow_id")) != str(workflow)
            or run.get("head_sha") != head or run.get("status") != "completed"
            or run.get("event") not in {"push", "workflow_dispatch"}
            or artifact.get("expired") or artifact.get("workflow_run", {}).get("id") != run.get("id")
            or artifact.get("workflow_run", {}).get("head_sha") != head):
        raise ValueError("CI repository/SHA/run/runner provenance mismatch")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest", "")):
        raise ValueError("CI artifact source digest unavailable")


def normalize_archive(data):
    tests, bugs, trees = [], [], []
    tests_present, bugs_present = False, False
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if len(archive.infolist()) > 5000 or sum(item.file_size for item in archive.infolist()) > 80_000_000:
            raise ValueError("CI archive exceeds limits")
        for item in archive.infolist():
            path = PurePosixPath(item.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in item.filename or (item.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Unsafe CI archive entry")
            if item.is_dir():
                continue
            if item.file_size > formats.MAX_BYTES:
                raise ValueError("CI evidence entry exceeds limits")
            content = archive.read(item)
            if path.name.startswith("TEST-") and path.suffix == ".xml":
                tests_present = True
                tests.extend(formats.junit(content))
            elif path.name in {"spotbugsXml.xml", "spotbugs.xml"}:
                bugs_present = True
                bugs.extend(formats.spotbugs(content))
            elif path.name == "dependency-tree.json":
                trees.append(json.loads(content))
    if len(trees) > 1:
        raise ValueError("Multiple module dependency trees are outside supported scope")
    return ([("tests", tests)] if tests_present else []) + ([("spotbugs", bugs)] if bugs_present else []) + ([("dependency_tree", trees[0])] if trees else [])


def main():
    parser = argparse.ArgumentParser()
    for name in ["repository", "run-id", "workflow-id", "base", "head"]:
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--artifact-name", default="single-module-evidence")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository) or ".." in args.repository:
        parser.error("Invalid repository")
    if not args.run_id.isdigit() or not args.workflow_id.isdigit() or not all(re.fullmatch(r"[0-9a-f]{40}", s) for s in [args.base, args.head]):
        parser.error("Pinned numeric run/workflow IDs and commit SHAs required")
    token = os.environ.get("GITHUB_TOKEN", "")
    api = "https://api.github.com/repos/" + args.repository + "/actions/"
    run = json.loads(get(api + "runs/" + args.run_id, token, 1_000_000))
    items = []
    for page in range(1, 11):
        listing = json.loads(get(api + "runs/" + args.run_id + f"/artifacts?per_page=100&page={page}", token, 1_000_000))["artifacts"]
        items.extend(item for item in listing if item["name"] == args.artifact_name)
        if len(listing) < 100:
            break
    else:
        raise ValueError("CI artifact pagination limit reached")
    if len(items) != 1:
        raise ValueError("Expected exactly one authorized CI artifact")
    artifact = items[0]
    verify_origin(run, artifact, args.repository, args.workflow_id, args.head)
    data = get(api + "artifacts/" + str(artifact["id"]) + "/zip", token)
    if "sha256:" + hashlib.sha256(data).hexdigest() != artifact["digest"]:
        raise ValueError("CI source archive digest mismatch")
    results = normalize_archive(data)
    if not results:
        raise ValueError("Missing CI evidence artifacts")
    origin = dict(repository=args.repository, run_id=args.run_id, workflow_id=args.workflow_id,
                  artifact_id=str(artifact["id"]), head_sha=args.head)
    entries = []
    for kind, content in results:
        canonical = json.dumps(content, separators=(",", ":"), ensure_ascii=False)
        entries.append(dict(snapshot="head", kind=kind, content=content,
                            sha256=hashlib.sha256(canonical.encode()).hexdigest()))
    bundle = dict(schema_version=2, base_sha=args.base, artifacts=entries,
                  origin=dict(archive_digest=artifact["digest"], workflow_url=run["html_url"]), **origin)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(bundle, indent=2) + "\n")
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(dict(verified_source="github_actions_api",
                          bundle_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest(), **origin), indent=2) + "\n")


if __name__ == "__main__":
    main()
