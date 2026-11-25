"""Exercise the exported assertion when an API changes an integer to a boolean."""

import hashlib
import json
from pathlib import Path
import runpy
import subprocess
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import patch

# The exporter is a CLI in this directory, outside the normal package roots.
sys.path.insert(0, str(Path.cwd() / "actions" / "behavior-replay"))
import write_test


SCENARIO = b'''def probe(value: int):
    from sample_api import quantity
    return quantity(value)
'''


def passes(test, quantity):
    with patch.dict(sys.modules, {"sample_api": SimpleNamespace(quantity=quantity)}):
        try:
            test()
        except AssertionError:
            return False
    return True


def probe(value: int):
    expected = "base" if value % 2 == 0 else "head"
    finding = {
        "target": "scenario.py::probe", "type": "int", "input": str(value),
        "probe_sha256": hashlib.sha256(SCENARIO).hexdigest(),
        "base": {"returned": value}, "head": {"returned": bool(value)},
    }
    with TemporaryDirectory() as directory:
        root = Path(directory)
        source, report, output = root / "scenario.py", root / "report.json", root / "test_export.py"
        source.write_bytes(SCENARIO)
        report.write_text(json.dumps({
            "schema_version": 1, "kind": "behavior", "status": "observed_difference",
            "base": {"sha": "1" * 40}, "head": {"sha": "2" * 40},
            "probe_modules": ["sample_api"], "findings": [finding], "notes": [],
        }), encoding="utf-8")
        export = subprocess.run(
            [sys.executable, "-IB", write_test.__file__, "--probe", source, "--report", report,
             "--expect", expected, "--output", output], check=False,
        )
        if export.returncode:
            return {"export_exit": export.returncode}
        test = runpy.run_path(str(output))["test_undertow_regression"]
        return {"expected": expected, "passes_on_base": passes(test, int),
                "passes_on_head": passes(test, bool)}
