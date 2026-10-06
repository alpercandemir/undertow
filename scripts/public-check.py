#!/usr/bin/env python3
"""Check publishable files for local data, workstation paths, and Turkish text.

This complements Gitleaks; it is not a complete secret detector or language classifier.
Only filenames and rule names are printed, never matching content or credentials.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TURKISH = re.compile(r"[\u0130\u0131\u015e\u015f\u011e\u011f\u00dc\u00fc\u00d6\u00f6\u00c7\u00e7]")
PERSONAL_PATH = re.compile(r"/(?:Users|home)/[A-Za-z0-9._-]+/|/(?:private/)?var/folders/")
PILOT_LINK = re.compile(r"https://github\.com/[\w.-]+/[\w.-]*-pilot(?:/|\b)")
PRIVATE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".sqlite", ".sqlite3"}
PRIVATE_NAMES = {"id_rsa", "id_ed25519", "service.local.json"}
PRIVATE_DIRECTORIES = {".secrets", ".credentials", ".aws", ".ssh", "service-data", "target"}
ENV_EXAMPLES = {".env.example", ".env.sample", ".env.template"}
CUSTOMER_POLICY = "demo/customer-service/.undertow/"


def git(*arguments):
    return subprocess.check_output(["git", "-C", str(ROOT), *arguments])


def candidate_paths():
    return sorted(set(git("ls-files", "-z", "--cached", "--others", "--exclude-standard")
                      .decode().split("\0")) - {""})


def path_issues(relative):
    path = Path(relative)
    issues = []
    if TURKISH.search(relative):
        issues.append("non_english_filename")
    if (path.name.startswith(".env") and path.name not in ENV_EXAMPLES
            or path.name in PRIVATE_NAMES or path.suffix in PRIVATE_SUFFIXES
            or path.name.endswith((".mv.db", ".trace.db"))
            or path.name.startswith("credentials") and path.suffix == ".json"):
        issues.append("credential_or_runtime_file")
    if PRIVATE_DIRECTORIES.intersection(path.parts):
        issues.append("private_or_generated_directory")
    if ".undertow" in path.parts and not relative.startswith(CUSTOMER_POLICY):
        issues.append("local_review_artifact")
    return issues


def content_issues(data, credentials):
    issues = []
    if any(value in data for value in credentials):
        issues.append("exact_environment_credential")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return issues
    if TURKISH.search(text):
        issues.append("turkish_text")
    if PERSONAL_PATH.search(text):
        issues.append("personal_workstation_path")
    if PILOT_LINK.search(text):
        issues.append("pilot_repository_link_requires_review")
    return issues


def check_files(paths, credentials):
    issues, files = [], []
    for relative in paths:
        path = ROOT / relative
        rules = path_issues(relative)
        if path.is_symlink():
            rules.append("symlink_requires_review")
        elif path.is_file():
            rules.extend(content_issues(path.read_bytes(), credentials))
            files.append(relative)
        elif path.exists():
            rules.append("non_regular_file_requires_review")
        for rule in rules:
            issues.append({"file": relative, "rule": rule})
    return issues, files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export", type=Path,
                        help="Copy reviewed candidate files to a new directory for secret scanning")
    args = parser.parse_args()
    credentials = [value.encode() for name, value in os.environ.items()
                   if len(value) > 15 and any(word in name.upper()
                                             for word in ("API_KEY", "TOKEN", "SECRET", "PASSWORD"))]
    issues, files = check_files(candidate_paths(), credentials)
    if issues:
        print(json.dumps({"files_checked": len(files), "issues": issues}, indent=2))
        return 1
    if args.export:
        destination = args.export.resolve()
        destination.mkdir(parents=True, exist_ok=False)
        for relative in files:
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, target)
    print(f"Public content check passed: {len(files)} files; no matching policy violations.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
