#!/usr/bin/env bash
# Install the play-data-safety skill and /data-safety command for your AI coding agent(s).
#
#   ./install.sh [--agent claude|gemini|codex|cursor|opencode|antigravity|all] [--global] [project-dir]
#
# Default: --agent all, into the current directory. --global installs into your home folder instead,
# so every project gets it. Nothing in your app's code is changed: only agent folders are written.
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT="all"; GLOBAL=0; DEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) AGENT="$2"; shift 2 ;;
    --global) GLOBAL=1; shift ;;
    -h|--help) sed -n 2,7p "$0"; exit 0 ;;
    *) DEST="$1"; shift ;;
  esac
done
if [ "$GLOBAL" = 1 ]; then DEST="$HOME"; else DEST="${DEST:-$PWD}"; fi
DEST="$(cd "$DEST" && pwd)"

skill() {   # skill <agent-dir>
  mkdir -p "$DEST/$1/skills"
  rm -rf "$DEST/$1/skills/play-data-safety"
  cp -R "$SRC/skills/play-data-safety" "$DEST/$1/skills/play-data-safety"
  echo "  skill    -> $DEST/$1/skills/play-data-safety"
}
cmd() {     # cmd <source> <target>
  mkdir -p "$(dirname "$DEST/$2")"
  cp "$SRC/commands/$1" "$DEST/$2"
  echo "  command  -> $DEST/$2"
}

install_one() {
  case "$1" in
    claude)      skill .claude;   cmd claude/data-safety.md .claude/commands/data-safety.md ;;
    gemini)      skill .gemini;   cmd gemini/data-safety.toml .gemini/commands/data-safety.toml
                 echo "  note: Agent Skills are experimental in Gemini CLI; enable them in settings, or just use /data-safety" ;;
    codex)       skill .codex
                 if [ "$GLOBAL" = 1 ]; then cmd codex/data-safety.md .codex/prompts/data-safety.md
                 else echo "  note: Codex reads custom prompts from ~/.codex/prompts only; run with --global for /data-safety"; fi ;;
    cursor)      skill .agents;   cmd cursor/data-safety.md .cursor/commands/data-safety.md ;;
    opencode)    skill .opencode; cmd opencode/data-safety.md .opencode/commands/data-safety.md ;;
    antigravity) skill .agents;   cmd antigravity/data-safety.md .agent/workflows/data-safety.md ;;
    *) echo "unknown agent: $1" >&2; exit 2 ;;
  esac
}

echo "Installing play-data-safety into $DEST"
if [ "$AGENT" = all ]; then
  for a in claude gemini codex cursor opencode antigravity; do echo "[$a]"; install_one "$a"; done
else
  echo "[$AGENT]"; install_one "$AGENT"
fi

if ! command -v java >/dev/null 2>&1; then
  echo "WARNING: 'java' not found. The scripts need JDK 17+ (Android Studio bundles one: set JAVA_HOME to it)."
fi
echo "Done. In your Android project, ask your agent: \"fill my Play data safety form\", or run /data-safety."
