# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## AI-DLC Plugin

This project uses the [AI-DLC](https://ai-dlc.dev) Claude Code plugin — a methodology for structured AI-driven development using role-based phases ("hats") and focused work units.

The plugin is configured in [.claude/settings.json](.claude/settings.json) and loads automatically when you open this project in Claude Code. Verify it's active by running `/elaborate` in a Claude Code session.

### Optional: Han CLI (state management)

```bash
brew install thebushidocollective/tap/han
# or
curl -fsSL https://han.guru/install.sh | bash
```

## Workflow

AI-DLC structures work around **Units** (focused pieces with clear success criteria) progressed through four hat phases:

| Command | Role |
|---------|------|
| `/researcher` | Research mode — gather context, understand the problem |
| `/planner` | Planning mode — define approach and success criteria |
| `/builder` | Building mode — implement the solution |
| `/reviewer` | Review mode — validate and improve |

Use `/elaborate` to start or continue work on a unit.

## Project Structure

```
.ai-dlc/          # Created automatically on first /elaborate run
                  # Stores intent definitions and unit specifications
                  # Commit this directory to preserve work history
.claude/
├── settings.json # Plugin configuration
└── settings.local.json
CLAUDE.md
```

## Companion Plugins

Add language-specific tooling as needed:

```
# TypeScript
npx han plugin install jutsu-typescript jutsu-biome --scope project

# Python
npx han plugin install jutsu-python jutsu-ruff --scope project

# Go
npx han plugin install jutsu-go --scope project
```
