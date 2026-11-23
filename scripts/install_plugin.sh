#!/usr/bin/env bash
# Automated install/check for the Review Atlas Bridge plugin (copy-into-plugins-dir strategy).
#
# Usage:
#   bash install_plugin.sh --check   [--jar <path>]   # report status only
#   bash install_plugin.sh --dry-run [--jar <path>]   # status + resolved target paths, writes nothing
#   bash install_plugin.sh --install [--jar <path>]   # copy jar into the IDE plugins dir (idempotent)
#
# Resolves the IDE config directory (newest IntelliJIdea* under the OS config root; override with
# IDEA_CONFIG_DIR=/path/to/IntelliJIdea2026.3). A jar copied while the IDE is running is loaded on the
# next start — that restart is the only step that cannot be automated.
#
# Output is KEY=VALUE lines for the calling agent:
#   STATUS=MISSING_CONFIG|MISSING_JAR|INSTALLED_CURRENT|INSTALLED_OUTDATED|INSTALLED|ERROR
#   CONFIG_DIR=...  TARGET=...  RUNNING=yes|no|unknown  RESTART_REQUIRED=yes|no
#   UNINSTALL=rm '<target>'
set -u

MODE="${1:-}"; shift || true
JAR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar) JAR="${2:-}"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

sha256() { # portable sha256; strips the backslash-escaping sha256sum adds for Windows paths
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{ sub(/^\\/, "", $1); print $1 }';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}';
  else echo "unavailable"; fi
}

config_root() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) echo "${APPDATA:-$HOME/AppData/Roaming}/JetBrains" ;;
    Darwin) echo "$HOME/Library/Application Support/JetBrains" ;;
    *) echo "${XDG_CONFIG_HOME:-$HOME/.config}/JetBrains" ;;
  esac
}

resolve_config() {
  if [[ -n "${IDEA_CONFIG_DIR:-}" ]]; then echo "$IDEA_CONFIG_DIR"; return; fi
  local root; root="$(config_root)"
  [[ -d "$root" ]] || return 1
  local newest
  newest="$(ls -dt "$root"/IntelliJIdea* "$root"/IdeaIC* 2>/dev/null | head -1)"
  [[ -n "$newest" ]] && echo "$newest"
}

detect_running() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      if command -v tasklist >/dev/null 2>&1; then
        if tasklist //FI "IMAGENAME eq idea64.exe" 2>/dev/null | grep -qi "idea64"; then echo yes; else echo no; fi
      else echo unknown; fi ;;
    Darwin)
      if pgrep -qx idea >/dev/null 2>&1; then echo yes; else echo no; fi ;;
    *)
      if pgrep -f "idea.*bin" >/dev/null 2>&1; then echo yes; else echo no; fi ;;
  esac
}

CFG="$(resolve_config || true)"
if [[ -z "$CFG" ]]; then
  echo "STATUS=MISSING_CONFIG"
  echo "HINT=启动一次 IDEA 后重试，或用 IDEA_CONFIG_DIR 指定配置目录，或退回手工安装（Settings→Plugins→Install from Disk）"
  exit 0
fi
CFG="${CFG//\\//}"   # normalize: mixed separators make sha256sum escape the filename and break hash compare
TARGET="$CFG/plugins/review-atlas-bridge.jar"
RUNNING="$(detect_running)"

status() {
  if [[ -n "$JAR" && -f "$JAR" && -f "$TARGET" ]]; then
    if [[ "$(sha256 "$JAR")" == "$(sha256 "$TARGET")" ]]; then echo INSTALLED_CURRENT; else echo INSTALLED_OUTDATED; fi
  elif [[ -f "$TARGET" ]]; then
    echo INSTALLED_CURRENT # no reference jar given: assume whatever is there is intended
  else
    echo MISSING_JAR
  fi
}

case "$MODE" in
  --check|--dry-run)
    echo "STATUS=$(status)"
    echo "CONFIG_DIR=$CFG"
    echo "TARGET=$TARGET"
    echo "RUNNING=$RUNNING"
    [[ "$MODE" == "--dry-run" ]] && echo "MODE=dry-run (nothing written)"
    ;;
  --install)
    if [[ -z "$JAR" || ! -f "$JAR" ]]; then
      echo "STATUS=ERROR"; echo "HINT=--jar <built review-atlas-bridge.jar> is required"; exit 2
    fi
    mkdir -p "$CFG/plugins"
    if [[ -f "$TARGET" ]] && [[ "$(sha256 "$JAR")" == "$(sha256 "$TARGET")" ]]; then
      echo "STATUS=INSTALLED_CURRENT"
    else
      if [[ -f "$TARGET" ]]; then cp -f "$TARGET" "$TARGET.bak.$(date +%Y%m%d%H%M%S)"; fi
      cp -f "$JAR" "$TARGET" || { echo "STATUS=ERROR"; echo "HINT=copy failed (permissions?)"; exit 3; }
      if [[ "$(sha256 "$JAR")" != "$(sha256 "$TARGET")" ]]; then
        echo "STATUS=ERROR"; echo "HINT=post-copy hash mismatch at $TARGET"; exit 3
      fi
      echo "STATUS=INSTALLED"
    fi
    echo "CONFIG_DIR=$CFG"
    echo "TARGET=$TARGET"
    echo "RUNNING=$RUNNING"
    if [[ "$RUNNING" == "yes" ]]; then
      echo "RESTART_REQUIRED=yes"
      echo "HINT=重启 IDEA 一次后插件生效（运行中放入的 jar 只能在下次启动加载）"
    else
      echo "RESTART_REQUIRED=no"
      echo "NEXT_START=jar 已就位，下次启动 IDE 时自动加载"
    fi
    echo "UNINSTALL=rm '$TARGET'"
    ;;
  *)
    echo "usage: install_plugin.sh --check|--dry-run|--install [--jar <path>]" >&2
    exit 2 ;;
esac
