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
| **Codex** | notify hook (agent-turn-complete) | tui.notifications (terminal bell) |
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
2. Auto-configures hook settings for each CLI tool (`~/.claude/settings.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`)
3. When a CLI tool completes a task or needs permission, the hook sends a POST request
4. Plugin displays IDE balloon notification + macOS Notification Center alert + plays sound
5. Dock badge increments; resets when IDE gains focus

## Installation

1. Download the latest release ZIP
2. In your IDE: **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk**
3. Select the ZIP file and restart the IDE
4. Hooks are auto-configured on first run — no manual setup needed

## Settings

**Settings** → **Tools** → **Terminal AI Watcher**

- Enable/disable notifications globally
- Enable/disable sound alerts
- Per-tool toggle: Claude Code, Codex, Gemini CLI

## Requirements

- Android Studio 2024.3+ or IntelliJ IDEA 2024.3+ (build 243+)
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

```bash
./gradlew buildPlugin
# Output: build/distributions/android-studio-terminal-alert-<version>.zip
```

## License

```
Copyright 2026 simjunbo

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
