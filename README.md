# Restartly

[![Build](https://img.shields.io/github/actions/workflow/status/Hauchdev/Restartly/build.yml?branch=main&label=build&logo=github)](https://github.com/Hauchdev/Restartly/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Hauchdev/Restartly?logo=github&sort=semver)](https://github.com/Hauchdev/Restartly/releases)
[![MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**The central restart management platform for Minecraft servers.**

Restartly is a server-side, multi-loader Minecraft mod that schedules, announces
and executes server restarts. It replaces fragile cron jobs and restart plugins
like *UltimateAutoRestart* with a fully configurable, event-driven system:

- multiple **schedule types** (daily, weekly, interval, dates, cron) running at
  the same time, with priority resolution
- fully customizable **countdowns** and **warnings** (chat, titles, action
  bar, boss bar, sounds, commands — each warning can have its own combo)
- **conditions** and **smart restart** (wait for empty, TPS gates, no-combat,
  no-event, automatic cancellation)
- **maintenance mode**, configurable **player kick**, **webhooks**, a public
  **API**, typed **events**, placeholders, timezone support and config
  **versioning with migrations**

```
Minecraft 1.20.1
├── Fabric  (mod loader)
└── Forge   (mod loader)
```

100% server-side. Vanilla clients connect normally — nobody needs to install
anything.

---

## Features

| Area | What you get |
|---|---|
| Scheduling | daily / weekly / interval / dates / cron, several simultaneously |
| Countdown | default length + any amount of warning steps (30s, 5m, 2h30m, ...) |
| Warnings | per-step chat, title, action bar, boss bar, sound, commands |
| Boss bar | configurable color/overlay, countdown progress, auto-cleanup |
| Conditions | min/max players, require empty, TPS above, no combat, no event |
| Smart restart | wait for conditions, retry interval, max delay + policy |
| Cancellation | manual or automatic via `cancel_if` rules |
| Maintenance | block joins, kick by permission, persisted across restarts |
| Kick | at countdown start, on maintenance, or right before shutdown |
| Commands | `/restartly` tree with tab completion, runtime schedule editing |
| Permissions | configurable nodes, op-level fallback, admin shortcut |
| Placeholders | `{time}`, `{players}`, `{reason}`, ... plus API-registered ones |
| Webhooks | Discord-compatible JSON posts, fully async, retry with backoff |
| API + Events | stable `RestartlyAPI` facade + typed events for other mods |
| Timezones | per-schedule zone overrides, DST handled via `java.time` |
| Persistence | minimal `state.yml` (last restart, maintenance) with safe recovery |
| Reload | validate-first, atomic swap, concrete error reporting, backups |
| Config versioning | `version:` field, migration registry, automatic backups |

## Installation

1. Install **Fabric** (Fabric API is required) or **Forge** for Minecraft **1.20.1**.
2. Drop `restartly-<loader>-1.20.1-<version>.jar` into the `mods/` folder.
3. Start the server once. Restartly creates `config/restartly/restartly.yml`
   with sensible defaults and logs the active schedules.
4. Edit the file and run `/restartly reload` — invalid changes are rejected and
   the previous configuration stays active.

Requires Java 17+.

## Configuration

Everything lives in `config/restartly/restartly.yml`. The bundled default file
is heavily commented; highlights:

```yaml
general:
  enabled: true
  timezone: "Europe/Madrid"

countdown:
  default: "10m"
  steps: ["30m", "15m", "10m", "5m", "3m", "2m", "1m", "30s", "10s", "5s", "4s", "3s", "2s", "1s"]

schedule:
  - id: daily
    type: daily          # daily | weekly | interval | dates | cron
    time: "04:00"
    priority: 0
    countdown: "10m"     # optional per-schedule override
```

Schedule types:

```yaml
- id: evening            # several daily times
  type: daily
  times: ["18:00", "22:00"]

- id: weekend
  type: weekly
  weekly:
    saturday: ["06:00"]
    sunday:  ["06:00"]

- id: every_6h
  type: interval
  interval: "6h"         # anchored to the last restart (persisted)

- id: special_day
  type: dates
  dates: ["2026-12-24 04:00"]

- id: cron_test
  type: cron
  cron: "0 4 * * *"      # 5-field cron; supports */n, a-b, lists, names
```

Warnings are completely free-form — each one may enable any combination of
actions:

```yaml
warnings:
  - time: "5m"
    title: { enabled: true, title: "<red>SERVER RESTART", subtitle: "<yellow>{time} remaining" }
    bossbar: { enabled: true, color: "RED", overlay: "PROGRESS", message: "Restarting in {time}" }
    sound: { enabled: true, sound: "minecraft:block.note_block.bell", volume: 1.0, pitch: 1.0 }

  - time: "1m"
    chat: { enabled: true, message: "{prefix}<yellow>Restarting in {time}." }
    actionbar: { enabled: true, message: "<red>{time} remaining" }
    commands: ["say Restarting in one minute"]
```

Every schedule can override `warnings`, `conditions`, `smart_restart`,
`maintenance`, `countdown` and `priority` on its own.

### Conditions & smart restart

```yaml
conditions:
  min_players: 0
  max_players: -1
  require_empty: false
  require_tps_above: 0.0
  require_no_combat: false
  require_no_active_event: false
  on_failure: "CANCEL"          # CANCEL | RESTART | WAIT
  cancel_if_players_above: -1
  cancel_if_event_active: false
  cancel_if_tps_below: 0.0

smart_restart:
  enabled: false
  wait_for_empty: true
  max_delay: "2h"
  retry_interval: "30s"
  max_delay_action: "RESTART"   # RESTART | CANCEL | FORCE
```

Flow: conditions are checked at the end of the countdown; when they are not
met the failure policy decides between cancelling, forcing or waiting. While
waiting, `cancel_if` rules are re-checked on every retry.

### Maintenance & kick

```yaml
maintenance:
  enabled: true
  activate_before: "5m"         # activates this long before the restart
  kick_players: false
  kick_message: "<red>Server restarting."
  block_join: true              # players without the bypass node are rejected

kick:
  enabled: true
  when: "SHUTDOWN"              # NONE | COUNTDOWN_START | MAINTENANCE_ACTIVATE | SHUTDOWN
  delay: "10s"
  message: |
    <red>Server restarting.

    <gray>Please reconnect shortly.
```

## Commands

| Command | Permission | Description |
|---|---|---|
| `/restartly restart [time] [--force] [--reason <text>]` | `restartly.restart` | Start a manual restart |
| `/restartly cancel [--reason <text>]` | `restartly.cancel` | Cancel the active restart |
| `/restartly status` | `restartly.status` | State, remaining time, players, next fire |
| `/restartly next` | `restartly.status` | Next scheduled restart |
| `/restartly warnings` | `restartly.status` | List configured warning steps |
| `/restartly reload` | `restartly.reload` | Validate + apply the config (keeps the old one on errors) |
| `/restartly schedule list` | `restartly.schedule` | List registered schedules |
| `/restartly schedule add <type> <value> [--countdown <d>]` | `restartly.schedule` | Add and reload a schedule |
| `/restartly schedule remove <id>` | `restartly.schedule` | Remove and reload a schedule |
| `/restartly maintenance on\|off\|toggle` | `restartly.maintenance` | Toggle maintenance mode |
| `/restartly debug on\|off\|toggle` | `restartly.debug` | Verbose logging toggle |
| `/restartly version` | `restartly.status` | Version, loader, config version |

Examples:

```
/restartly restart 10m
/restartly restart 30s --force
/restartly restart 5m --reason "Weekly maintenance"
/restartly schedule add daily 06:00 --countdown 5m
```

## Permissions

All nodes are configurable in `permissions:` and fall back to operator level
2 (4 for the admin node) when no permission provider is present.

| Node | Default |
|---|---|
| admin (implies all) | `restartly.admin` |
| restart | `restartly.restart` |
| cancel | `restartly.cancel` |
| status | `restartly.status` |
| reload | `restartly.reload` |
| schedule | `restartly.schedule` |
| maintenance | `restartly.maintenance` |
| bypass (kick/join) | `restartly.bypass` |
| maintenance.bypass | `restartly.maintenance.bypass` |
| debug | `restartly.debug` |

The `bypass` node exempts a player from maintenance join blocking *and* from
the pre-restart kick (both are checked with the bypass node).

## Placeholders

Resolved in every message, title, action bar, boss bar and kick/join message.

| Placeholder | Value |
|---|---|
| `{time}` | countdown clock `mm:ss` / `h:mm:ss` |
| `{seconds}` / `{minutes}` / `{hours}` | numeric remaining |
| `{players}` / `{max_players}` | online / player cap |
| `{server_version}` | e.g. `1.20.1` |
| `{reason}` | restart reason (`none` when unset) |
| `{schedule}` | triggering schedule id (`manual` for manual restarts) |
| `{timezone}` | active zone id |
| `{state}` | current Restartly state (`IDLE`, `COUNTDOWN`, ...) |
| `{uptime}` | server uptime (`1d 2h 3m`) |
| `{prefix}` | the configured chat prefix (chat messages only) |

Custom placeholders can be registered from other mods (see API below).

### Message formatting

Messages use MiniMessage-style tags implemented natively (no extra library):
`<red>`, `<dark_gray>`, `<gold>`, `<#ff8800>`, `<bold>`, `<b>`, `<italic>`,
`<underline>`, `<strikethrough>`, `<obfuscated>`, `<reset>`, closing tags and
real newlines from YAML block scalars. Unknown tags are left visible so typos
are easy to spot.

## Integrations

All integrations are optional, disabled by default, and never a hard
dependency.

### Webhooks

```yaml
integrations:
  webhook:
    enabled: false
    url: "https://discord.com/api/webhooks/..."
    retries: 2
    events:
      restart_scheduled: true
      restart_started: true
      restart_warning: true
      restart_completed: true
      restart_cancelled: true
      restart_waiting: true
```

Payloads are posted as JSON on a single dedicated daemon thread with retry +
backoff; the server thread is never blocked. The URL is never written to logs
(only its host, at DEBUG level). Discord embeds work out of the box.

Future integrations (LuckPerms, PlaceholderAPI, Plan, BlueMap/Dynmap, Tebex,
Discord bot) plug into the same `Integration` contract:
`configure → start → shutdown`, gated by config.

## API

Other mods depend on `dev.hauch.restartly.api.RestartlyAPI` — a stable, small
facade. Everything else in Restartly is internal.

```java
// Control
RestartlyAPI.scheduleRestart(Duration.ofMinutes(10), "Scheduled by my mod");
RestartlyAPI.scheduleRestartAt(Instant futureInstant, "Event restart");
RestartlyAPI.cancelRestart("no longer needed");
RestartlyAPI.setMaintenance(true);

// Query
RestartlyAPI.isRestarting();
RestartlyAPI.getState();            // RestartState enum
RestartlyAPI.getRemainingTime();    // Duration
RestartlyAPI.getNextRestart();      // Optional<Instant>
RestartlyAPI.isMaintenance();

// Extend
RestartlyAPI.registerPlaceholder("my_value", ctx -> "...");
RestartlyAPI.registerCondition(ctx -> myConditionIsMet());
RestartlyAPI.subscribe(RestartEvent.RestartStarted.class,
        event -> broadcastToMyNetwork(event.reason()));

// Config
RestartlyConfig cfg = RestartlyAPI.getConfig();
```

### Events

Fired on the server thread, in order:

- `RestartEvent.RestartScheduled`
- `RestartEvent.RestartStarted`
- `RestartEvent.RestartWarning`
- `RestartEvent.RestartWaiting`
- `RestartEvent.RestartCancelled`
- `RestartEvent.RestartCompleted`
- `RestartEvent.MaintenanceChanged`

## Supported versions

| Minecraft | Fabric | Forge |
|---|---|---|
| 1.20.1 | ✅ | ✅ |

The platform layer is deliberately thin; porting to NeoForge/1.21 only touches
the loader modules, not the common code.

## Building

```bash
./gradlew build
```

Produces `fabric/build/libs/restartly-fabric-1.20.1-<version>.jar` and
`forge/build/libs/restartly-forge-1.20.1-<version>-all.jar`. The Forge artifact
(`-all`) is shaded with SnakeYAML (relocated to
`dev.hauch.restartly.lib.snakeyaml`) and reobfuscated to SRG; the Fabric
artifact ships SnakeYAML via Fabric's nested-jar support.

## Testing

```bash
./gradlew test
```

Runs the pure-logic suite (duration parsing, cron, schedule calculation with
DST, conditions, placeholders, config parsing/validation/migration, warning
timelines, scheduler polling/priority and the restart state machine) — all
without booting a server.

## Continuous integration

Every push and pull request is verified by GitHub Actions
([.github/workflows/build.yml](.github/workflows/build.yml)):

- `common`, `fabric` and `forge` are **built and tested in parallel** workers,
  so the memory-hungry Minecraft pipelines never compete for the 7 GB runner
  memory.
- The **gradle wrapper checksum** is validated on every run.
- Gradle's dependency cache and the generated **Minecraft artifacts** (Loom
  remaps, NeoForm/Forge sources) are cached between runs — PRs restore the
  cache but never write to it.
- Compilable jars are uploaded as **build artifacts** for manual testing.

Releases are driven by git tags. To cut one:

```bash
./scripts/release.sh 1.1.0      # bumps gradle.properties + adds the CHANGELOG section
# edit the new CHANGELOG section, commit via PR, then:
git tag v1.1.0 && git push origin v1.1.0
```

The tag triggers [.github/workflows/release.yml](.github/workflows/release.yml),
which validates the version, builds the jars **from the tagged commit** and
publishes a **GitHub Release** — its body is the `## [vX.Y.Z]` section of
`CHANGELOG.md` (auto-generated notes as fallback). Full details in
[CONTRIBUTING.md](CONTRIBUTING.md#releasing).

Dependency bumps are handled by [Dependabot](.github/dependabot.yml) (weekly
checks for both GitHub Actions and Gradle).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT — see [LICENSE](LICENSE).