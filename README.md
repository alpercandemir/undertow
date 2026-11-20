<p align="center">
  <img src="docs/assets/undertow-logo.png" alt="Undertow" width="200">
</p>

# Undertow

Undertow is a GitHub Action that reviews changes to application behavior. Each finding names the affected flow, describes the before and after behavior, and gives a hypothetical example with links to the source at the compared commits.

> **Free delivery now includes orders of exactly 50.** An order for 50 took the paid-delivery path before the change and takes the free-delivery path afterward.

## Add Undertow to CI

Copy the [ready-to-use workflow](docs/examples/change-review.yml) into `.github/workflows/undertow.yml`. It uses the released Action and adds an advisory job summary, a Markdown and JSON artifact, and an optional PR comment. GitHub's hosted Ubuntu runner supplies everything Undertow needs.

The default source analysis covers common numeric condition changes, changed literal returns, and added or removed HTTP route declarations. To enable broader interpretation across languages and files, pass the optional `GEMINI_API_KEY` secret to the action. This sends a bounded selection of source to Google. [Integration and analysis details](docs/change-review.md)

[See the showcase](https://github.com/alpercandemir/undertow-showcase) for a small before-and-after example.
