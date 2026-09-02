# Changelog

All notable changes to Restartly are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/) and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-09-02

Initial production release.

### Added
- Multi-loader build for Minecraft 1.20.1 (Fabric + Forge), server-side only.
- YAML configuration system with validation, per-field error reporting,
  config versioning (`version: 1`) and a migration registry with automatic
  backups on upgrade.
- Scheduling: daily (`time` / `times`), weekly (per-day times), interval
  (anchored to the last restart), explicit dates and 5-field cron. Multiple
  schedules coexist; simultaneous fires resolve by priority.
- Per-schedule overrides: countdown length, warnings, conditions, smart
  restart, maintenance and priority.
- Countdown engine with an arbitrary list of warning steps; each warning can
  combine chat, titles, action bar, boss bar, sounds and console commands.
  Per-step commands via `commands.on_<step>`.
- Boss bar with configurable color/overlay, countdown progress and automatic
  cleanup on cancel/completion/state change.
- Conditions: min/max players, require-empty, TPS gate, no-combat, no active
  event; failure policy `CANCEL | RESTART | WAIT`; `cancel_if` rules.
- Smart restart: waits for conditions, retry interval, `max_delay` and
  `max_delay_action` (`RESTART | CANCEL | FORCE`).
- Maintenance mode: block joins, kick non-bypass players, persisted through
  server restarts in `state.yml`.
- Player kick with configurable timing (`COUNTDOWN_START`,
  `MAINTENANCE_ACTIVATE`, `SHUTDOWN`) and message.
- `/restartly` command tree with suggestions: restart (+`--force`,
  `--reason`), cancel, status, next, reload, warnings, schedule
  list/add/remove, maintenance, debug, version.
- Configurable permission nodes with op-level fallback and an admin shortcut.
- Placeholders (`{time}`, `{players}`, `{reason}`, ...) + API registration.
- MiniMessage-style text formatting implemented natively.
- Webhook integration (Discord-compatible JSON), fully async, retries with
  backoff, URLs never logged.
- Public `RestartlyAPI` facade and typed events
  (scheduled/started/warning/waiting/cancelled/completed/maintenance).
- Persistent `state.yml` (last restart anchor for interval schedules,
  maintenance flag) with atomic writes and crash-safe recovery.
- `/restartly reload`: validate first, keep the previous configuration on
  errors, swap atomically, report every problem.
- SLF4J logging throughout; debug toggle; nightly-restart oriented default
  configuration.
- JUnit test suite (64 tests): duration parsing, cron, schedule calculation
  incl. DST, conditions, placeholders, config parsing/validation/migrations,
  warning timelines, scheduler polling/priority, restart state machine.

### Technical notes
- SnakeYAML 2.2 is bundled with the Fabric jar (nested jars) and relocated +
  shaded into the Forge jar; no extra files needed on production servers.
- No Mixins, no client code, no threads on the server path, no
  `Thread.sleep`, no `System.out` usage.