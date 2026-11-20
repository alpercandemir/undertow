# Business change review

Undertow compares the committed source for two Git revisions. It describes supported behavior changes as before-and-after flows and supplies a hypothetical example for each finding. Source references identify the path and line range on the relevant revision.

## GitHub Actions

Copy the [workflow](examples/change-review.yml) into `.github/workflows/undertow.yml` on the base branch. It uses the published `v0.2.1` action. You can pin the corresponding full commit SHA for immutable action selection. The action fetches the event commits into a temporary bare Git repository. It compares the PR's merge base to its exact head and writes the result to the job summary and to a Markdown and JSON artifact.

The example updates one GitHub Actions bot comment per PR. It uses `pull_request_target` with `contents: read` and `pull-requests: write` so the trusted workflow can also report on fork PRs. The action never checks out or executes the candidate. Keep this job separate from jobs that build or run PR code. If your repository's Actions policy restricts `pull_request_target`, allow this trusted reporting workflow there or use `pull_request` with `contents: read` and remove the `comment` input for a summary-only setup. [GitHub's event security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)

The action checks that the PR is still open and that its base and head match the event before publishing. A write failure appears in the summary. Findings, missing model access, and unavailable analyses are advisory. On a push event, the action uses the event's before and after commits if both exist. For workflows dispatched manually, pass both `base` and `head` as full commit SHAs.

## How it reads changes

The engine identifies changed text on both revisions and ignores conventional test and fixture paths, documentation, common generated paths, and sensitive file paths. It takes bounded regions from the remaining differences, including surrounding lines. Source file names and content come from Git objects. Neither the model nor the local engine runs project code.

The local rules recognize common numeric conditions, changed literal returns, and HTTP route declarations in common languages. They compute an example at a changed threshold and describe only the supported local effect. Changes outside these patterns may produce an empty report. The report shows the reviewed scope and any omissions caused by its limits.

For recognized functions and methods, Undertow searches the full tracked source tree of each Git revision with `git grep`. It follows names of enclosing callers for up to three steps and links the matched source on the relevant revisions. A result such as `gift_checkout → shipping_fee → qualifies_for_free_delivery` gives reviewers a path to investigate, including code outside the diff. Name matching can miss dynamic dispatch, aliases and generated code, and same-name functions may be unrelated.

For broader business-flow analysis, add a repository Actions secret named `GEMINI_API_KEY` and pass it through the Undertow step's environment. This explicitly opts into sending changed source, filenames, and selected supporting excerpts to the Gemini Developer API. Obvious credential patterns are filtered. Check Google's data terms for your API tier before sharing private source. The default model is `gemini-3.8-flash`. Set `UNDERTOW_GEMINI_MODEL` on the step to use a different Gemini model. [Gemini model documentation](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash) · [Gemini API terms](https://ai.google.dev/gemini-api/terms)

```yaml
- uses: alpercandemir/undertow/actions/change-review@v0.2.1
  env:
    GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
```

The model groups related changes across files. It can request nearby source and a lookup of up to four identifiers already present in the review. Undertow supplies excerpts from the automatic reference trace and searches for requested symbols within the same bounded process. Excerpts can include unchanged callers or definitions. The model receives only selected excerpts. Every finding cites a changed region and can also cite the supporting source. It falls back to local rules if the model is unavailable or returns unusable output. Source references locate the basis for a finding. The lookup can miss dynamic calls, indirect dependencies and matches beyond its budget. The report keeps conclusions tied to the source it reviewed.
