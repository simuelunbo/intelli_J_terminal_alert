# Terminal AI Watcher

IntelliJ Platform plugin that monitors AI CLI tools (Claude Code, Codex, Gemini CLI) running in the IDE terminal and delivers real-time notifications.

## Features

- **Hook-based detection** — Zero false positives using each tool's official hook system
- **macOS native notifications** — IDE icon displayed, click activates the IDE
- **Dock badge counter** — Unread notification count, auto-resets on IDE focus
- **IDE-only filtering** — External terminals are ignored (JetBrains terminal only)
- **Multi-IDE support** — Android Studio and IntelliJ IDEA run simultaneously
- **Per-tool settings** — Enable/disable notifications for each CLI tool individually

## Supported Tools

| Tool | Completion | Permission Prompt |
|------|:----------:|:-----------------:|
| **Claude Code** | Stop hook | Notification hook (permission_prompt) |
| **Codex** | notify hook (agent-turn-complete) | PermissionRequest hook in `~/.codex/hooks.json` |
| **Gemini CLI** | AfterAgent hook | Notification hook |

## How It Works

```
Claude Code / Codex / Gemini CLI
  └─ Hook event fires
      └─ curl → localhost HTTP server (dynamic port)
          └─ PPID chain routing → correct IDE instance
              └─ IDE balloon notification + macOS system notification + sound
```

1. Plugin starts an HTTP server on a dynamic port inside the IDE process
2. Auto-configures hook settings for each CLI tool (`~/.claude/settings.json`, `~/.codex/config.toml` + `~/.codex/hooks.json`, `~/.gemini/settings.json`)
3. When a CLI tool completes a task or needs permission, the hook sends a POST request
4. Plugin displays IDE balloon notification + macOS Notification Center alert + plays sound
5. Dock badge increments; resets when IDE gains focus

## Installation

1. Download the latest release ZIP
2. In your IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk**
3. Select the ZIP file and restart the IDE
4. Hooks are auto-configured on first run. In Codex, review any new hook in `/hooks` before it can run.

Codex permission hooks use `hooks.json`; the plugin migrates its unchanged legacy TOML block and preserves other tools' hooks, trust records, and completion notification wrappers. Configuration backups use the `.terminal-watcher.bak` suffix. Customized legacy blocks are preserved and reported in the IDE log instead of adding a duplicate.

Claude hooks are registered in user settings only. Missing approval or completion handlers are added independently; other handlers, events, and settings are preserved. Changes keep a `.terminal-watcher.bak` copy and use atomic replacement after checking for intervening edits. Existing project settings are left intact, and new shared project settings are not created. Invalid settings or customized matchers for the same command are preserved and reported in the IDE log.

Claude approval alerts retain `Notification/permission_prompt`: terminal notifications arrive after about six seconds without typing, including sandbox network approvals. This is not an immediate `PermissionRequest` alert. See the [Claude hook reference](https://code.claude.com/docs/en/hooks#notification).

Permission requests bypass completion notification throttling, including consecutive requests with identical text. Windows sound playback resolves a missing default (including the macOS `Glass` default) to an available Windows sound without requiring a settings save. System banners still follow the IDE's foreground/background notification policy; IDE balloons are a separate channel.

## Settings

**Settings** → **Tools** → **Terminal AI Watcher**

- Enable/disable notifications globally
- Enable/disable sound alerts
- Per-tool toggle: Claude Code, Codex, Gemini CLI

## Requirements

- Android Studio or IntelliJ IDEA 2026.1+ (build 261+)
- macOS (for system notifications and sound)
- CLI tools installed: [Claude Code](https://claude.ai/code), [Codex](https://openai.com/codex), [Gemini CLI](https://ai.google.dev/gemini-api/docs/gemini-cli)

## Architecture

```
src/main/kotlin/com/terminalwatcher/
├── TerminalWatcherPlugin.kt      # Entry point (ProjectActivity)
├── hook/
│   ├── HookHttpServer.kt         # Dynamic port HTTP server
│   ├── HookConfigHelper.kt       # Auto-configures CLI tool hooks
│   └── HookEvent.kt              # Event models + @Serializable payload
├── dispatch/
│   └── NotificationDispatcher.kt # Routes events to notifications
├── mac/
│   └── MacNotifier.kt            # IDE balloon + SystemNotifications + badge
├── settings/
│   ├── SettingsState.kt           # Persistent settings (SimplePersistentStateComponent)
│   └── SettingsConfigurable.kt    # Settings UI panel
└── terminal/
    └── TerminalTabTracker.kt      # Terminal tab name resolution
```

## Building

The core module emits Java 21 bytecode and defaults to a Java 21 toolchain. If only a newer JDK is installed, select it with `-PbuildJavaVersion=25` (for JDK 25); Java and Kotlin output still target Java 21.

The build compiles against a locally installed IDE (Android Studio) as the IntelliJ Platform.
It is auto-detected from the default install location on macOS, Windows and Linux;
if none is found, Android Studio `platformFallbackVersion` (see `gradle.properties`) is downloaded instead.

```bash
./gradlew buildPlugin
# Output: core/build/distributions/core-<version>.zip

# Use a specific IDE install (Windows/Linux: install root, macOS: *.app/Contents)
./gradlew buildPlugin -PideLocalPath="C:/Program Files/Android/Android Studio"
# or: export TERMINAL_ALERT_IDE_HOME="/Applications/Android Studio.app/Contents"
```

## Compatibility verification

```bash
# Verify the ZIP against the build IDE (no extra IDE download when using a local installation).
./gradlew :core:verifyPlugin

# Also verify a specific IntelliJ IDEA EAP build; downloads that IDE if needed.
./gradlew :core:verifyPlugin "-PverificationIdeVersion=263.4732.28"

# Override the local verification IDE without changing the compilation IDE.
./gradlew :core:verifyPlugin -PverificationIdePath="/path/to/IDE"
```

Reports are written to `core/build/reports/pluginVerifier`. Verification is an explicit task, not added to every test run. Experimental API findings remain visible.

The Java `TerminalWatcherEnvCustomizer` is the only production class that imports the experimental shell-start types. It inherits the IDE's default working-directory method without a Kotlin compatibility bridge. Kotlin setup receives a standard environment setter callback and retains port readiness, tab registration, and focus binding. The required shell-start API remains experimental; this boundary reduces unnecessary references and isolates future API changes, but does not guarantee compatibility with future IDEs.

## License

```
Copyright 2026 simjunbo

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
