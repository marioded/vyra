# Contributing to Vyra

This project is small and the bar for merging is intentional. The public API stays tiny, the core stays dependency-free
and every change ships with minimal tests.

## Before starting

- Read [ARCHITECTURE.md](ARCHITECTURE.md). It documents the routing model, the concurrency model, and the tradeoffs that
  were already made. If your change fights one of those tradeoffs, say so in the PR description. It may still be the
  right call but it needs a discussion.
- Check the [ADRs](docs/adr) before proposing something that touches the public API, module boundaries or failure
  semantics. New architectural decisions get an ADR to explain the tradeoffs and the reasoning.

## Setting up

- Java 17, Gradle 9.7.1 (wrapper included).
- `.\gradlew.bat build` on Windows, `./gradlew build` elsewhere.
- The `vyra-redis` integration tests use Testcontainers and need Docker. If you don't have Docker, run the other
  modules: `.\gradlew.bat :vyra-core:test :vyra-inmemory:test :vyra-jackson:test :vyra-gson:test`.

## Code style

- Formatting is enforced by [Spotless](https://github.com/diffplug/spotless) with google-java-format. Run
  `.\gradlew.bat spotlessApply` before committing; `spotlessCheck` runs as part of the build.
- Every source file carries the Apache 2.0 license header. Spotless adds and checks it automatically so don't hand-edit
  headers.
- Keep the public API small. New public methods need a concrete use case and a test.
- `vyra-core` must stay free of third-party runtime dependencies. Infrastructure concerns belong in the transport
  modules.

## Tests

- New behavior ships with tests. Unit tests for logic, integration tests (Testcontainers) for the Redis transport.
- Concurrency and timeout behavior are part of the contract. For example, if your change touches `RequestManager`,
  `MessageDispatcher`, or the handler executor, the tests should cover races, late responses, and executor saturation.
- Run the full suite before opening the PR: `.\gradlew.bat test`.

## Opening a pull request

1. Fork the repo and create a branch with a descriptive name (`fix/request-timeout-race`, `feat/nats-transport`).
2. Make the change. One PR should be one change. If you have multiple unrelated changes, open multiple PRs.
3. Add or update tests.
4. Run `.\gradlew.bat build` and make sure everything is green, including Spotless.
5. Open the PR. In the description, state what changed, why, and how you verified it. If the change touches the public
   API or module boundaries, note the backwards-compatibility impact. Always document everything that is not obvious. If
   the change is a refactor, explain why it was needed and what it improves.

## What gets merged

- Correct, tested, and formatted code that fits the architecture.
- Changes that keep the public API small and the core dependency-free.
- Changes that document their tradeoffs.

## What gets pushed back

- Refactors that churn large parts of the codebase without a behavior change or a stated reason.
- New abstractions without a concrete use case.
- Changes that break the public API or module boundaries without a discussion of the tradeoffs.