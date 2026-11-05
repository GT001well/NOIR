# Contributing

Thanks for taking a look. NOIR is a small Kotlin Multiplatform project; the whole
pipeline is testable without a phone.

## Setup

- JDK 17+
- Android platform-tools (`adb`) for end-to-end runs
- `jadx` only if you touch the `deep` command

```bash
./gradlew :core:jvmTest          # unit tests, no device needed
./gradlew :cli:installDist       # build the CLI
./cli/build/install/noir/bin/noir --about
```

On Windows the launcher is `cli\build\install\noir\bin\noir.bat`.

## Layout

```
core/
  commonMain/     models, rules, classifier, advisor, report, TUI (pure Kotlin)
  jvmMain/        adb wrapper, dumpsys parser, APK string scan, jadx, setup
  jvmTest/        parser / classifier / report / TUI tests
cli/
  Main.kt         command dispatch, guided flow, progress, error rendering
  Guidance.kt     user-facing text: device onboarding, about, friendly errors
```

Keep `commonMain` free of JVM imports — the Android and iOS targets are planned.

## Where to add things

| Change | File |
|---|---|
| New sensitive permission to flag | `core/src/commonMain/.../Rules.kt` |
| New category or vendor | `core/src/commonMain/.../Classifier.kt` + `ClassifierTest` |
| New remediation heuristic | `core/src/commonMain/.../Advisor.kt` |
| New CLI command | `cli/src/main/kotlin/app/noir/cli/Main.kt` |
| New user-facing text | `cli/src/main/kotlin/app/noir/cli/Guidance.kt` |

## Rules of the road

1. **Never widen what `auto --apply` does without a test.** It may only change
   reversible app-ops.
2. **Parsers need fixtures.** If you touch `Dumpsys.parse` or APK scanning, add a
   test with a realistic snippet — the shared-uid bug is exactly that class of
   problem.
3. **Width matters.** Any new dashboard or box line must survive `COLUMNS=50`.
   `TuiTest` asserts this.
4. **No new network calls.** The tool must stay offline apart from the explicit
   `noir setup` download of adb / jadx; scanning and reporting must never reach
   the network.
5. **Japanese/Chinese text** in output is fine; keep it short enough for narrow
   terminals (the renderer wraps, but tables do not scroll).

## Commits

- imperative subject, ≤ 72 chars: `fix(dumpsys): merge shared-uid permissions`
- explain *why* in the body if it is not obvious
- one logical change per PR

## Pull requests

- `./gradlew :core:jvmTest :cli:test :cli:installDist` must pass
- add a line to `CHANGELOG.md` under `Unreleased`
- if the change affects output, paste a before/after snippet

## License

By contributing you agree that your work is released under the MIT license and
that the copyright holder is **Verlintas**.
