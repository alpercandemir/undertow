#!/usr/bin/env python3
"""Create three real commits and verify a Spring major upgrade with local JARs.

No model/replay, network requests, Maven resolution or changes to the root Git repo.
Run Undertow's evidence investigation separately using the command in README.md.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = "src/main/java/example/PaymentStatus.java"
OLD, NEW = "5.3.31", "6.0.7"


def git(repo, *args):
    return subprocess.check_output([
        "git", "-c", "user.name=Undertow Demo", "-c",
        "user.email=demo@undertow.invalid", "-c", "commit.gpgsign=false",
        "-C", str(repo), *args,
    ], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--maven-repo", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--java-home", type=Path, help="JDK 17+ for the sample compilation")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Choose a new output directory; previous evidence is preserved")
    java = str(args.java_home / "bin/java") if args.java_home else "java"
    javac = str(args.java_home / "bin/javac") if args.java_home else "javac"
    jars = {}
    for version in (OLD, NEW):
        jars[version] = [
            args.maven_repo.resolve() / "org/springframework" / artifact / version
            / f"{artifact}-{version}.jar"
            for artifact in ("spring-web", "spring-core", "spring-jcl")
        ]
        for jar in jars[version]:
            if not jar.is_file():
                parser.error(f"Missing cached JAR: {jar}. Resolve Spring {version} first.")
    compiler_version = subprocess.check_output([javac, "-version"], text=True).strip()
    repo = output / "repo"
    repo.mkdir(parents=True)
    shutil.copytree(ROOT / "config", repo / "config")
    shutil.copyfile(ROOT / "CODING-SKILL.md", repo / "CODING-SKILL.md")
    # Register exact-tag official source alongside release notes in the trusted BASE.
    # ResponseEntity's source supplies the version-specific return-type signatures.
    (repo / "config/dependency-sources.yaml").write_text(
        "sources:\n"
        "  org.springframework:spring-web:\n"
        "    - https://raw.githubusercontent.com/spring-projects/spring-framework/"
        "v{version}/spring-web/src/main/java/org/springframework/http/ResponseEntity.java\n"
        "    - https://github.com/spring-projects/spring-framework/releases/tag/v{version}\n"
        "allowed_hosts: [github.com, raw.githubusercontent.com]\n", encoding="utf-8")
    pom = (HERE / "pom.xml").read_text(encoding="utf-8")
    original = (HERE / SOURCE).read_text(encoding="utf-8")
    fixed = original.replace("import org.springframework.http.HttpStatus;",
                             "import org.springframework.http.HttpStatusCode;").replace(
                                 "HttpStatus status =", "HttpStatusCode status =")
    (repo / SOURCE).parent.mkdir(parents=True)
    git(repo, "init", "-q")
    stages = [("base", OLD, original), ("upgrade", NEW, original), ("fixed", NEW, fixed)]
    commits = {}
    for name, version, source in stages:
        (repo / "pom.xml").write_text(pom.replace(OLD, version), encoding="utf-8")
        (repo / SOURCE).write_text(source, encoding="utf-8")
        git(repo, "add", ".")
        git(repo, "commit", "-qm", f"{name}: Spring Web {version}")
        git(repo, "tag", name)
        commits[name] = git(repo, "rev-parse", "HEAD")
    (output / "upgrade.diff").write_text(git(repo, "diff", "base", "upgrade") + "\n")
    (output / "fix.diff").write_text(git(repo, "diff", "upgrade", "fixed") + "\n")

    results = []
    for name, version, _ in stages:
        check = output / "checks" / name
        source_file = check / SOURCE
        source_file.parent.mkdir(parents=True)
        source_file.write_text(git(repo, "show", f"{commits[name]}:{SOURCE}") + "\n")
        (check / "pom.xml").write_text(git(repo, "show", f"{commits[name]}:pom.xml") + "\n")
        classes = check / "classes"
        classes.mkdir()
        classpath = os.pathsep.join(map(str, jars[version]))
        # Keep the expected-error assertion independent of the machine's language.
        command = [javac, "-J-Duser.language=en", "-J-Duser.country=US", "--release", "17",
                   "-cp", classpath, "-d", str(classes), str(source_file)]
        compiled = subprocess.run(command, capture_output=True, text=True, timeout=60)
        (check / "compile.log").write_text(compiled.stdout + compiled.stderr)
        result = dict(stage=name, version=version, commit=commits[name],
                      compile_exit=compiled.returncode, command=command,
                      jars=[dict(path=str(j), sha256=hashlib.sha256(j.read_bytes()).hexdigest())
                            for j in jars[version]])
        if name == "upgrade":
            result["expected_result_observed"] = (
                compiled.returncode != 0 and "incompatible types" in compiled.stderr
                and "HttpStatusCode" in compiled.stderr and "HttpStatus" in compiled.stderr)
        elif compiled.returncode == 0:
            run = subprocess.run([java, "-cp", str(classes) + os.pathsep + classpath,
                                  "example.PaymentStatus"], capture_output=True, text=True, timeout=30)
            (check / "run.log").write_text(run.stdout + run.stderr)
            result["runtime_exit"] = run.returncode
            result["expected_result_observed"] = run.returncode == 0 and "PASS:" in run.stdout
        else:
            result["expected_result_observed"] = False
        results.append(result)
        print(f"{name}: Spring {version}, compile exit {compiled.returncode}, "
              f"expected result observed: {result['expected_result_observed']}", flush=True)
    summary = dict(compiler=compiler_version, repo=str(repo), commits=commits, checks=results,
                   provenance="Compiler/runtime checks executed by the demo, not Undertow or CI")
    (output / "verification.json").write_text(json.dumps(summary, indent=2) + "\n")
    if not all(result["expected_result_observed"] for result in results):
        raise SystemExit("Unexpected result; inspect checks/*/*.log and verification.json")
    print(f"Verified base → broken upgrade → fixed: {output}")


if __name__ == "__main__":
    main()
