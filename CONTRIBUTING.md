# Contributing to Restartly

Thanks for wanting to help! Restartly aims to stay small, correct and
production-ready. The following conventions keep it that way.

## Project layout

```
common/    everything that is not loader specific (compiled against vanilla 1.20.1)
fabric/    Fabric entry point, platform adapter, loader event hooks
forge/     Forge entry point, platform adapter, loader event hooks
buildSrc/  shared Gradle conventions (multiloader-common / multiloader-loader)
```

Rule: **common never imports loader APIs.** If a feature needs loader access,
add the method to `RestartPlatform` and implement it in both loaders.

## Conventions

1. **No Mixins** unless there is a real technical need — so far there is none.
2. **No client code.** Restartly is server-side only.
3. **No `System.out`** — use `Restartly.LOGGER`. `e.printStackTrace()` is not
   acceptable either.
4. **No `Thread.sleep`** and no blocking the server thread. Timers go through
   the tick loop; background work goes through dedicated daemon executors.
5. **No `catch (Exception ignored)`** — log the message or handle the case.
6. **Small classes, one responsibility.** The state machine, config parsing,
   scheduling and messaging are deliberately separate.
7. **Pure logic is unit tested.** Anything that does not need Minecraft (time
   math, cron, conditions, placeholders, validation) goes into
   `common/src/test` and must stay deterministic — pass values in, assert
   values out, no hidden clocks.
8. **Config changes are migrations.** Never just change the meaning of a key;
   bump `version`, add a migration in `ConfigMigrator` and keep a backup.
9. Every event that other mods can observe goes through `RestartEvent` and is
   fired on the server thread.

## Porting / adding a loader

1. Create the new module (copy the loader template used by `fabric/`).
2. Implement `RestartPlatform` (see the Fabric/Forge adapters, they are
   deliberately tiny).
3. Wire `SERVER_STARTED`, tick, command registration, join/quit and damage
   hooks into `RestartlyCore`.
4. Run the full build: `./gradlew build`.

## Submitting changes

1. Branch, implement, add tests when the change touches logic.
2. Run `./gradlew test` and `./gradlew build` — both must pass.
3. Update `CHANGELOG.md` under "Unreleased"/the next version.
4. Open a pull request with a short summary of the *why*.

> **Git on Windows:** CI runs `sh gradlew` so a missing executable bit on the
> wrapper never breaks the build, but please keep it healthy for others too —
> `git update-index --chmod=+x gradlew` before committing avoids
> `Permission denied` errors for contributors on Linux/macOS.

## Releasing

Releases are driven by git tags (`vX.Y.Z`); the pipeline lives in
`.github/workflows/release.yml` and is triggered automatically when the tag is
pushed. To cut a release:

1. Run `./scripts/release.sh <new-version>` (e.g. `./scripts/release.sh 1.1.0`).
   It bumps `version=` in `gradle.properties` and inserts a new
   `## [<version>]` section under `## [Unreleased]` in `CHANGELOG.md`.
2. Fill in the new CHANGELOG section and commit `gradle.properties` +
   `CHANGELOG.md` (through a pull request when the branch is protected).
3. Tag the merged commit and push the tag:
   ```
   git tag v1.1.0
   git push origin v1.1.0
   ```
4. The workflow validates that the tag matches `gradle.properties`, builds
   the jars from the **tagged commit**, and publishes the GitHub Release
   using the `## [<version>]` section of the CHANGELOG as the body (falling
   back to auto-generated notes when the section is missing).

Manual re-runs are possible from the Actions tab (`workflow_dispatch`) by
providing an existing tag.

## Development environment

- JDK 17 (Temurin recommended).
- IntelliJ IDEA: import the root project, open the Gradle tab and run the
  `Fabric Server` / `Forge Server` run configurations (auto-generated).
- First build downloads Minecraft 1.20.1 and sets up mappings — give it time.