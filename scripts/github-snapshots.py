#!/usr/bin/env python3
"""GitHub.com source adapter. Credentials are scoped to fetch, never stored in Git."""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def validate(metadata):
    repository = metadata["repository"]
    if (not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}", repository)
            or ".." in repository or repository.startswith(".")):
        raise ValueError("Invalid repository")
    for key in ["base_sha", "head_sha"]:
        if not re.fullmatch(r"[0-9a-f]{40}", metadata[key]):
            raise ValueError("Invalid commit SHA")
    return repository


def fetch(metadata, output):
    repository = validate(metadata)
    output.mkdir(parents=True, exist_ok=False)
    environment = {k: v for k, v in os.environ.items()
                   if not k.startswith("GIT_") and k not in {"GEMINI_API_KEY", "GITHUB_TOKEN", "GITHUB_FETCH_TOKEN"}}
    environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                       GIT_TERMINAL_PROMPT="0")
    token = os.environ.get("GITHUB_FETCH_TOKEN", os.environ.get("GITHUB_TOKEN", ""))
    with tempfile.TemporaryDirectory(prefix="undertow-fetch-") as temporary:
        askpass = Path(temporary) / "askpass"
        askpass.write_text(
            '#!' + sys.executable + '\nimport os,sys\n'
            'sys.stdout.write("x-access-token" if "username" in sys.argv[1].lower() '
            'else os.environ.get("UNDERTOW_FETCH_CREDENTIAL", ""))\n', encoding="utf-8")
        askpass.chmod(0o700)
        environment.update(GIT_ASKPASS=str(askpass), UNDERTOW_FETCH_CREDENTIAL=token)
        prefix = ["git", "-c", "core.hooksPath=" + os.devnull,
                  "-c", "credential.helper=", "-c", "http.followRedirects=false"]
        commands = [prefix + ["init", "--bare", str(output)],
                    prefix + ["-C", str(output), "fetch", "--no-tags", "--depth=2000",
                              "https://github.com/" + repository + ".git",
                              metadata["base_sha"], metadata["head_sha"]]]
        for command in commands:
            result = subprocess.run(command, env=environment, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=180, check=False)
            if result.returncode:
                raise RuntimeError("Git snapshot acquisition failed; verify separate fetch credentials and commit access")
        environment.pop("UNDERTOW_FETCH_CREDENTIAL", None)
        result = subprocess.run(prefix + ["-C", str(output), "merge-base",
                                           metadata["base_sha"], metadata["head_sha"]],
                                env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, timeout=20, check=False)
        if result.returncode or not re.fullmatch(r"[0-9a-f]{40}", result.stdout.strip()):
            raise RuntimeError("Merge base unavailable within 2000 commits; acquire authorized history explicitly")
        metadata.update(analysis_diff_base=result.stdout.strip(),
                        target_head=metadata["base_sha"], policy_commit=metadata["base_sha"])
    return metadata


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    metadata = fetch(json.loads(args.metadata.read_text(encoding="utf-8")), args.output)
    args.metadata.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
