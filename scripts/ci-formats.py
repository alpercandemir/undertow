#!/usr/bin/env python3
"""Normalize bounded, pre-produced JUnit/SpotBugs output; never execute tests."""

import xml.etree.ElementTree as ET

MAX_BYTES = 1_000_000


def xml_root(data):
    if len(data) > MAX_BYTES or b"\x00" in data or b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise ValueError("Oversized or unsafe XML evidence")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise ValueError("CI XML must use UTF-8") from None
    return ET.fromstring(text)


def junit(data):
    root = xml_root(data)
    result = []
    for case in root.iter("testcase"):
        if any(case.find(kind) is not None for kind in ("flakyFailure", "flakyError", "rerunFailure", "rerunError")):
            raise ValueError("JUnit retries/flaky results need independent execution identities")
        name = case.get("name", "")
        if not name:
            raise ValueError("JUnit case name missing")
        status = ("error" if case.find("error") is not None else
                  "failed" if case.find("failure") is not None else
                  "skipped" if case.find("skipped") is not None else "passed")
        if sum(case.find(kind) is not None for kind in ("error", "failure", "skipped")) > 1:
            raise ValueError("Conflicting JUnit case outcomes")
        result.append({"name": case.get("classname", "") + "." + name, "status": status})
    if root.tag not in {"testsuite", "testsuites"}:
        raise ValueError("Unsupported JUnit document")
    suites = list(root.iter("testsuite"))
    if root.tag == "testsuites":
        suites.append(root)
    for suite in suites:
        cases = list(suite.iter("testcase"))
        counts = {"tests": len(cases), "failures": sum(c.find("failure") is not None for c in cases),
                  "errors": sum(c.find("error") is not None for c in cases),
                  "skipped": sum(c.find("skipped") is not None for c in cases)}
        for attribute, count in counts.items():
            if suite.get(attribute) is not None and int(suite.get(attribute)) != count:
                raise ValueError("JUnit declared counts do not match execution results")
        if suite.find("error") is not None or suite.find("failure") is not None:
            raise ValueError("JUnit suite-level failure has no individual execution identity")
    return result


def spotbugs(data, source_prefix="src/main/java/"):
    root = xml_root(data)
    if root.tag != "BugCollection":
        raise ValueError("Unsupported SpotBugs document")
    result = []
    for bug in root.iter("BugInstance"):
        source = next((line for line in bug.iter("SourceLine")
                       if line.get("sourcepath") and int(line.get("start", "0")) > 0), None)
        if source is None:
            raise ValueError("SpotBugs finding has no source mapping")
        path = source_prefix + source.get("sourcepath")
        if path.startswith("/") or any(part in {"", ".", ".."} for part in path.split("/")) or "\\" in path:
            raise ValueError("Invalid SpotBugs source path")
        result.append({"type": bug.get("type", ""), "path": path,
                       "line": int(source.get("start")), "priority": bug.get("priority", "")})
    return result
