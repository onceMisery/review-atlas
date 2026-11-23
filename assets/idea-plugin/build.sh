#!/usr/bin/env bash
# Build the Review Atlas Bridge jar without Gradle:
#   - compile with the IDE's bundled JBR javac against <IDE>/lib/*.jar (classpath via @argfile)
#   - package with any JDK `jar` on PATH (JBR ships no jar tool)
# Install: IDEA Settings -> Plugins -> gear -> Install Plugin from Disk -> dist/review-atlas-bridge.jar
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Locate an IDE installation WITHOUT hardcoding any user path:
#   1. $IDEA_HOME wins; 2. common install roots per OS (JetBrains dir, Toolbox, /Applications, /opt);
#   pick the candidate whose bin/build.txt is newest (matches the most recently installed build).
ide_candidates() {
  [[ -n "${IDEA_HOME:-}" ]] && printf '%s\n' "$IDEA_HOME"
  ls -d "/c/Program Files/JetBrains/IntelliJ IDEA"* 2>/dev/null
  ls -d "${LOCALAPPDATA:-$HOME/AppData/Local}/JetBrains/Toolbox/apps/IDEA"*/*/* 2>/dev/null
  ls -d "/Applications/IntelliJ IDEA"*.app/Contents 2>/dev/null
  ls -d "$HOME/.local/share/JetBrains/Toolbox/apps/IDEA"*/*/* 2>/dev/null
  ls -d /opt/idea* /opt/intellij* 2>/dev/null
}
IDE=""
while IFS= read -r cand; do
  [[ -n "$cand" && -x "$cand/jbr/bin/javac" ]] && { IDE="$cand"; break; }
done < <(ide_candidates | while IFS= read -r p; do
           printf '%s\t%s\n' "$(stat -c %Y "$p/bin/build.txt" 2>/dev/null || echo 0)" "$p"
         done | sort -rn | cut -f2-)
if [[ -z "$IDE" ]]; then
  echo "No IntelliJ installation found. Set IDEA_HOME to your IntelliJ installation root." >&2
  exit 1
fi
if ! command -v jar >/dev/null 2>&1; then
  echo "No 'jar' tool on PATH. Install a JDK (Temurin) or add its bin to PATH." >&2
  exit 1
fi

OUT="$HERE/out"
DIST="$HERE/dist"
rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"

# Windows javac cannot take a 20KB classpath on one command line and cannot read /c/... paths.
# 2026.3+ moved JCEF classes out of lib/ into plugins/jcef-plugin/lib/modules/ — include both.
CP="$( { for j in "$IDE"/lib/*.jar; do cygpath -m "$j"; done; \
        for j in "$IDE"/plugins/jcef-plugin/lib/modules/*.jar; do [ -f "$j" ] && cygpath -m "$j"; done; } | paste -sd';' -)"
ARGS="$OUT/javac.args"
{
  printf -- '--release 21\n-encoding UTF-8\n-d "%s"\n-cp "%s"\n' "$(cygpath -m "$OUT")" "$CP"
  find "$HERE/src" -name '*.java' | while read -r f; do cygpath -m "$f"; done
} > "$ARGS"
"$IDE/jbr/bin/javac" "@$(cygpath -m "$ARGS")"
rm -f "$ARGS"
cp -r "$HERE/src/META-INF" "$OUT/"
cp -r "$HERE/src/icons" "$OUT/"
jar cf "$DIST/review-atlas-bridge.jar" -C "$OUT" .
echo "built: $DIST/review-atlas-bridge.jar"
