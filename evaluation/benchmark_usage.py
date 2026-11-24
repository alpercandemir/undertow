"""Observe response usage while running the released worker with inter-case pacing."""

import json
from pathlib import Path
import runpy
import sys
import time
from urllib.error import HTTPError
from urllib.request import OpenerDirector, Request

import run


def observe(engine):
    requests = []
    opener = OpenerDirector.open

    def capture(self, request, *args, **kwargs):
        if not isinstance(request, Request) or request.host != "generativelanguage.googleapis.com":
            return opener(self, request, *args, **kwargs)

        entry = {}
        requests.append(entry)
        try:
            response = opener(self, request, *args, **kwargs)
        except HTTPError as error:
            entry["http_status"] = error.code
            raise
        except OSError as error:
            entry["error"] = type(error).__name__
            raise
        entry["http_status"] = response.status
        read_body = response.read

        def capture_read(*args, **kwargs):
            body = read_body(*args, **kwargs)
            try:
                envelope = json.loads(body)
                tokens = envelope.get("usageMetadata", {})
                entry["usage"] = {
                    name: tokens[name] for name in (
                        "promptTokenCount", "cachedContentTokenCount", "candidatesTokenCount",
                        "thoughtsTokenCount", "totalTokenCount",
                    ) if type(tokens.get(name)) is int
                }
                version = envelope.get("modelVersion")
                if isinstance(version, str) and version.startswith("gemini-"):
                    entry["model_version"] = version
            except (ValueError, AttributeError):
                pass
            return body

        response.read = capture_read
        return response

    output = Path(sys.argv[sys.argv.index("--output") + 1])
    OpenerDirector.open = capture
    try:
        runpy.run_path(str(engine), run_name="__main__")
    finally:
        output.mkdir(parents=True, exist_ok=True)
        run.write(output / "provider-usage.json", {"requests": requests})


def main():
    original = run.run_case
    ran_case = False

    def measured(engine, case, repo, output, env):
        nonlocal ran_case
        if ran_case:
            time.sleep(12)
        ran_case = True
        wrapper = engine.with_name("observed_worker.py")
        if not wrapper.exists():
            wrapper.write_text(
                "import sys\n"
                f"sys.path.insert(0, {str(Path(__file__).resolve().parent)!r})\n"
                "from pathlib import Path\n"
                "from benchmark_usage import observe\n"
                f"observe(Path({str(engine)!r}))\n",
                encoding="utf-8",
            )
        return original(wrapper, case, repo, output, env)

    run.run_case = measured
    return run.main()


if __name__ == "__main__":
    sys.exit(main())
