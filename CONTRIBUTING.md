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

## Development environment

- JDK 17 (Temurin recommended).
- IntelliJ IDEA: import the root project, open the Gradle tab and run the
  `Fabric Server` / `Forge Server` run configurations (auto-generated).
- First build downloads Minecraft 1.20.1 and sets up mappings — give it time.