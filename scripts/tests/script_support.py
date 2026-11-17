"""Load repository scripts without treating the working directory as code."""
import importlib.util
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]

def load_script(filename):
    spec = importlib.util.spec_from_file_location(filename, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
