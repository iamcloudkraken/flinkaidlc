# flinkaidlc

AI-driven development project using the [AI-DLC](https://ai-dlc.dev) methodology with Claude Code.

## Overview

This project uses AI-DLC — a structured approach to AI-assisted software development using role-based phases ("hats") and focused work units.

## Getting Started

### Prerequisites

- [Claude Code](https://claude.ai/code) CLI
- [han CLI](https://han.guru) (optional, for state management)

```bash
brew install thebushidocollective/tap/han
```

### Workflow

AI-DLC structures work through four hat phases:

| Command | Role |
|---------|------|
| `/researcher` | Research mode — gather context, understand the problem |
| `/planner` | Planning mode — define approach and success criteria |
| `/builder` | Building mode — implement the solution |
| `/reviewer` | Review mode — validate and improve |

Run `/elaborate` in a Claude Code session to start or continue work on a unit.

## Project Structure

```
.ai-dlc/          # Intent definitions and unit specifications
.claude/
├── settings.json # Plugin configuration
CLAUDE.md         # Project instructions for Claude Code
```
