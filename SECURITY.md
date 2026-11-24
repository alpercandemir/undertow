# Security

Undertow reviews Git objects as data. The GitHub Action fetches candidate history into a temporary bare repository and runs its own code from a trusted revision. It gives the model bounded source context and accepts only references to regions supplied by the engine.

The default local analysis does not send source to a model provider. Setting `GEMINI_API_KEY` sends source excerpts and filenames to Google. Path filters and credential-pattern redaction have limited coverage. Use provider access consistent with your repository's data policy. [Gemini API terms](https://ai.google.dev/gemini-api/terms)

Opt-in [behavior replay](docs/behavior-replay.md) and [pytest replay](docs/test-replay.md) execute project code. Run them in a separate job on an ephemeral runner with read-only permissions and without secrets or persisted checkout credentials. Publish from a fresh same-repository PR job with comment permissions. Keep project checkout and runtime execution in the read-only job. Use trusted revisions for local replay. [GitHub's workflow security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

Report security issues through the [repository's private vulnerability reporting page](https://github.com/alpercandemir/undertow/security/advisories/new).
