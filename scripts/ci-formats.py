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
        name = case.get("name", "")
        if not name:
            raise ValueError("JUnit case name missing")
        status = ("error" if case.find("error") is not None else
                  "failed" if case.find("failure") is not None else
                  "skipped" if case.find("skipped") is not None else "passed")
        result.append({"name": case.get("classname", "") + "." + name, "status": status})
    if root.tag not in {"testsuite", "testsuites"}:
        raise ValueError("Unsupported JUnit document")
    for suite in root.iter("testsuite"):
        if suite.get("tests") is not None and int(suite.get("tests")) != len(list(suite.iter("testcase"))):
            raise ValueError("JUnit declared test count does not match execution results")
    if root.tag == "testsuites" and root.get("tests") is not None and int(root.get("tests")) != len(result):
        raise ValueError("JUnit aggregate count does not match execution results")
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
