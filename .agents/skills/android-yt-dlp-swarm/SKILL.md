---
name: android-yt-dlp-swarm
description: Coordinate multi-agent implementation and review for the yt-dlp Android app, including Kotlin services/UI, Chaquopy Python integration, FFmpeg media processing, queue persistence, licensing, and end-to-end validation.
---

# Android yt-dlp Swarm

Use this skill when a change to the yt-dlp Android project is large enough to benefit from parallel agents, spans independent Android/Python/build/documentation concerns, or needs a coordinated implementation and review. Do not use a swarm for a focused change that can be safely completed in one or two edits.

## Project context

This project wraps upstream yt-dlp in a native Kotlin Android app using Chaquopy. The current app includes URL batching, format selection, playlist support, a persistent SQLite queue, a foreground download service, MediaStore publishing, and Python downloader tests. Audio-only MP3 conversion is implemented through a bundled FFmpegKit Android dependency. Treat paths and dependency versions as facts to verify in the current checkout; do not assume this context remains unchanged.

## Swarm roles and boundaries

Before delegating, inspect the project structure, current git/worktree state when available, the relevant source, and test/build instructions. Give every agent the exact requirement, context, files it may edit, expected output, and any test command it should run.

For a typical cross-layer feature, use separate, non-overlapping scopes:

1. **Android UI and interaction agent** — owns only UI/activity or resource files. It may report required service/API changes but must not edit service implementation.
2. **Service and persistence agent** — owns service, queue/store, and Android lifecycle behavior. It must preserve durable state transitions, cancellation semantics, foreground-service requirements, and existing queue behavior.
3. **Python/yt-dlp agent** — owns Python integration and Python tests. It must retain URL validation, callback-failure isolation, progress semantics, and safe output-path checks.
4. **Build/dependency/licensing agent** — investigates and edits only Gradle, manifest/packaging, and licensing/documentation files. Require primary-source verification for artifact coordinates, versions, supported ABIs, native codecs/encoders, Android page-size/SDK compatibility, and licenses.
5. **Integration/review agent** — read-only by default. It checks API agreement, race conditions, error paths, duplicated assumptions, tests, and project-wide diagnostics. Assign edits only if its write scope is explicitly disjoint from implementation agents.

Not every task needs every role. Prefer two or three useful agents over speculative delegation.

## Required coordination

- Assign disjoint write sets. Never have two agents independently modify the same file or the same public API surface.
- Name one integration owner (the lead agent). The lead owns shared interface decisions, merges/synthesizes recommendations, resolves conflicts, and makes final cross-layer edits.
- When interfaces cross agent boundaries, define them first: intent extras, queue fields/states, Python function arguments/events, cancellation/pause contracts, output-file ownership, and error behavior.
- Ask agents to report changed paths, decisions, assumptions, commands run, test results, and unresolved risks. Do not accept “done” without those details.
- Do not let delegated agents commit, rebase, create branches, or revert user changes.
- Read agent-edited files before integration; do not assume a clean patch merely because a sub-agent reports success.

## Android and yt-dlp engineering constraints

- Keep yt-dlp as the upstream Python package embedded with Chaquopy; do not reimplement extractors or claim complete CLI parity.
- Keep media downloads off the main thread. For sustained background downloads, preserve foreground-service notification and lifecycle behavior appropriate to the current target SDK.
- Treat queue state transitions as durable contracts. A queued item must not be stranded by worker shutdown; retry, pause, resume, cancellation, and process-death recovery must have explicit state semantics.
- Distinguish cancellability from pausing. Only expose pause when the active operation can actually pause; allow cancellation for conversion/publication if those operations can be interrupted safely.
- Invoke FFmpeg via an argument-array API, never by interpolating paths into a shell command. Keep intermediate outputs private, validate nonempty successful outputs, clean temporary files on failure/cancel, and retain source media until the final output is safely published.
- Confirm that the chosen FFmpeg artifact really contains the requested encoder (for MP3, `libmp3lame`) and required ABIs. Do not infer encoder availability from a decoder-only package. Pin exact dependency versions.
- Review native dependency size, ABI filters, Android SDK and 16 KB page-size support, maintenance history, notices, and applicable LGPL/GPL obligations before proposing a binary dependency. Do not present a license as legal advice.
- Validate Python-side behavior with the repository's unit tests. Validate Android changes with compilation/tests when JDK, Gradle, Android SDK, and network/dependencies are available. Clearly report unavailable validation rather than treating diagnostics as a successful build.
- Add regression tests for changed behavior where the project supports them. If Android behavior cannot be exercised by existing tests, state the gap and propose the smallest useful test or device check.

## Suggested workflow

1. Inspect current source, tests, build configuration, repository instructions, and uncommitted user changes.
2. Split the work into independently verifiable tasks with precise file ownership.
3. Establish shared contracts before parallel implementation.
4. Delegate implementation or research; use a separate read-only reviewer for high-risk changes.
5. Integrate and inspect every changed file. Resolve API mismatches and test the combined feature, not just each isolated part.
6. Run the narrowest relevant tests first, then broader tests/builds. Inspect project diagnostics and diff/status where available.
7. Summarize the architecture and changes, validation performed, environmental blockers, dependency/licensing concerns, and anything still unverified.

## Agent prompt template

Use this template when creating a subtask:

> **Task:** [one concrete outcome].
> **Context:** [project behavior, related API contracts, constraints].
> **Write scope:** [exact files or directories; do not edit outside this scope].
> **Validation:** [specific tests or checks].
> **Report:** changed files, implementation decisions, commands/results, and unresolved risks. Do not commit, create branches, or revert existing work.
