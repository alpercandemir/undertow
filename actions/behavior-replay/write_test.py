"""Export a standalone pytest test for a reviewed probe finding."""

import argparse
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from discover import fingerprint, load_probe
from run_cases import CONVERT, serializable


def input_literal(kind, value):
    if kind.startswith("list[") and isinstance(value, list):
        return "[" + ", ".join(input_literal(kind[5:-1], item) for item in value) + "]"
    if kind not in CONVERT or not isinstance(value, str):
        raise ValueError("The report contains an unsupported input.")
    number = CONVERT[kind](value)
    if kind == "Decimal":
        if number.is_finite():
            return f"Decimal({ascii(value)})"
    elif kind == "int" or math.isfinite(number):
        return repr(number)
    raise ValueError("The report contains a non-finite input.")


def render(source, kind, finding, expected_side, examples=None):
    if kind != finding["type"] or hashlib.sha256(source).hexdigest() != finding.get("probe_sha256"):
        raise ValueError("The supplied probe differs from the one that produced this finding.")
    if kind == "json":
        name = finding.get("case")
        if name not in examples or json.dumps(examples[name], sort_keys=True) != json.dumps(finding["input"], sort_keys=True):
            raise ValueError("The named input differs from the case in the supplied probe.")
        argument = ascii(examples[name])
    else:
        argument = input_literal(kind, finding["input"])
    outcome = finding[expected_side]
    lines = ["", "", "", "def test_undertow_regression():"]
    lines.append("    import json")
    if "Decimal" in kind:
        lines.append("    from decimal import Decimal")
    lines.append("")
    if any("raised" in finding[side] for side in ("base", "head")):
        if "raised" in outcome:
            expected = {"raised": outcome["raised"]}
        else:
            expected = {"returned": serializable(outcome["returned"], json_only=True)}
        lines.extend([f"    expected = {ascii(expected)}", "    try:",
                      f"        observed = {{'returned': probe({argument})}}",
                      "    except Exception as error:", "        observed = {'raised': type(error).__name__}"])
    else:
        expected = serializable(outcome["returned"], json_only=True)
        lines.extend([f"    expected = {ascii(expected)}", f"    observed = probe({argument})"])
    lines.extend(["    assert json.dumps(observed, sort_keys=True, allow_nan=False) == json.dumps(",
                  "        expected, sort_keys=True", "    )"])
    return source.rstrip() + ("\n".join(lines) + "\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--probe", required=True, type=Path)
    parser.add_argument("--finding", help="Evidence ID. Required when the report contains multiple findings.")
    parser.add_argument("--expect", choices=["base", "head"], required=True,
                        help="Revision whose reviewed result becomes the assertion.")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"))
        if (report["schema_version"], report["kind"], report["status"]) != (1, "behavior", "observed_difference"):
            raise ValueError("Expected a behavior report with an observed difference.")
        findings = report["findings"]
        if args.finding:
            findings = [finding for finding in findings if fingerprint(finding) == args.finding]
        if len(findings) != 1:
            raise ValueError("Select exactly one finding with --finding.")
        source, _, kind, examples = load_probe(args.probe)
        test = render(source, kind, findings[0], args.expect, examples)
        compile(test, str(args.output), "exec")
        with args.output.open("xb") as output:
            output.write(test)
    except (OSError, ValueError, KeyError, TypeError, SyntaxError, InvalidOperation) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
