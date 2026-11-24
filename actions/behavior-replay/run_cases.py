"""Evaluate generated inputs against one exported Python module."""

from decimal import Decimal
import importlib
import json
import math
from pathlib import Path
import sys


CONVERT = {"int": int, "float": float, "Decimal": Decimal}


def serializable(value, depth=0):
    kind = type(value)
    if value is None or kind in {bool, int}:
        return value
    if kind is float and math.isfinite(value):
        return value
    if kind is Decimal and value.is_finite():
        return str(value)
    if kind is str and len(value) <= 160:
        return value
    if depth < 2 and kind in {list, tuple} and len(value) <= 12:
        return [serializable(item, depth + 1) for item in value]
    if (depth < 2 and kind is dict and len(value) <= 12
            and all(type(key) is str and len(key) <= 80 for key in value)):
        return {key: serializable(item, depth + 1) for key, item in value.items()}
    raise ValueError("Unsupported result")


def invoke(function, argument):
    try:
        value = function(argument)
    except Exception as error:
        return {"raised": type(error).__name__[:80]}
    try:
        result = {"returned": serializable(value)}
        if len(json.dumps(result)) <= 400:
            return result
    except (ValueError, OverflowError):
        pass
    return None


def main():
    request_path, result_path = map(Path, sys.argv[1:])
    request = json.loads(request_path.read_text(encoding="utf-8"))
    root = Path.cwd()
    source = root / request["path"]
    relative = source.relative_to(root / "src") if source.is_relative_to(root / "src") else source.relative_to(root)
    sys.path[:0] = [str(root / "src"), str(root)]
    module = importlib.import_module(".".join(relative.with_suffix("").parts))
    if Path(module.__file__).resolve() != source:
        raise ImportError("Import did not resolve to the selected module")
    function = getattr(module, request["name"])
    kind = request["type"]
    if kind.startswith("list["):
        convert = CONVERT[kind[5:-1]]
        arguments = [[convert(item) for item in value] for value in request["inputs"]]
    else:
        arguments = list(map(CONVERT[kind], request["inputs"]))
    results = [invoke(function, argument) for argument in arguments]
    result_path.write_text(json.dumps(results, sort_keys=True), encoding="utf-8")


if __name__ == "__main__":
    main()
