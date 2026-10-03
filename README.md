# OSRS Goal Generator Companion

A local-only RuneLite sensor for the separate OSRS Goal Generator desktop app. The desktop is the command center; this plugin supplies observed skill XP/levels and Collection Log pages. It does not choose actions or automate gameplay.

## What it records

- Current RSN, combat level, total level/XP, individual skill levels/XP, and connection state.
- Session XP deltas and a periodic heartbeat (default five seconds; effective range two to ten).
- Exact item IDs, names, and obtained flags on Collection Log pages the player opens.
- A per-account local page cache and recent collection-unlock message names.

Only observed pages are known. Cached pages are not proof that the entire log is current. Boss KC and ranks remain desktop HiScores data. This companion does not provide recommendations or an interactive sidebar of its own.

## Local data

The only bridge files are `sync.json` and `collection-cache.json` under RuneLite's plugin-data sandbox for `osrs-goal-generator-companion`. Temporary `.tmp` files are used for atomic replacement. The desktop reads `sync.json`; see [schema v1](docs/SYNC_SCHEMA_V1.md).

The companion adds no network requests, telemetry, remote service, credential handling, process inspection, or arbitrary file chooser. It does not read passwords, login tokens, or bank PINs. Local bridge files contain account progress and should not be uploaded when reporting issues.

## Development on Windows

Use the included Gradle 8.10 wrapper with a compatible JDK. JDK 21 was verified; compilation targets Java 11 bytecode. Do not use JDK 25/26 with Gradle 8.10. Set JAVA_HOME to your installed JDK directory, then run:

```powershell
.\gradlew.bat --project-cache-dir .gradle-user --no-daemon clean build
.\gradlew.bat --project-cache-dir .gradle-user --no-daemon run
```

The run task loads this plugin into a development client. The test-source main class launches RuneLite. Seven deterministic JUnit tests separately cover account isolation, lifecycle races, client-thread shutdown, malformed cache recovery, and write ordering. `build=standard` allows Plugin Hub to supply its standard build configuration. Runtime version and bytecode target are distinct; there is no Java-11-only runtime guard and no Lombok dependency.

For Jagex accounts, follow the official [development-login guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) yourself. This plugin does not configure or handle development credentials.

## Validation

Locally verified on Windows: compilation, development startup, enabled plugin, live desktop XP changes, observed Collection Log updates, and cache restoration after client restart. Desktop goal completion was verified with a real skill goal and a real observed collection-item unlock. These are project validation results, not Plugin Hub approval. This plugin is not yet published to Plugin Hub.

Get the separate [desktop app and Windows setup instructions](https://github.com/Jayeh-Code/osrs-goal-generator). Download its source ZIP, extract it, run Setup Desktop.cmd once, then Start Desktop.cmd. Python 3.11+ is required. The desktop is an alpha; this plugin is not yet available in Plugin Hub.

## License

BSD 2-Clause; see [LICENSE](LICENSE). Metadata currently credits OSRS Goal Generator Project.
