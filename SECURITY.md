# Security

Undertow reviews Git objects as data. The GitHub Action fetches candidate history into a temporary bare repository and runs its own code from a trusted revision. It gives the model bounded source context and accepts only references to regions supplied by the engine.

The default local analysis does not send source to a model provider. Setting `GEMINI_API_KEY` sends source excerpts and filenames to Google. Path filters and credential-pattern redaction have limited coverage. Use provider access consistent with your repository's data policy. [Gemini API terms](https://ai.google.dev/gemini-api/terms)

The opt-in [test replay Action](docs/test-replay.md) executes project code. Run it on a separate test job on an ephemeral runner with read-only permissions and without secrets or persisted checkout credentials. To publish replay results, use a fresh same-repository PR job that reads the artifacts and has comment permissions. Keep project checkout and test execution in the read-only job. For local replay, use trusted revisions. [GitHub's workflow security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

Report security issues through the [repository's private vulnerability reporting page](https://github.com/alpercandemir/undertow/security/advisories/new).
