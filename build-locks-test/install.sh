#!/bin/sh
# Patches an Eclipse 4.41 installation with the test build of
# "project-level build locks" (eclipse.platform branch build-yield-enforcement).
#
#   install.sh <eclipse dir>           patch the two jars (backup first)
#   install.sh <eclipse dir> restore   put the original jars back
#
# <eclipse dir> is the folder that holds plugins/ and eclipse.ini.
# On macOS: /Applications/Eclipse.app/Contents/Eclipse
set -eu
ECL=${1:?usage: install.sh <eclipse dir> [restore]}
ACTION=${2:-install}
HERE=$(cd "$(dirname "$0")" && pwd)
BACKUP="$HOME/eclipse-build-locks-backup"
INFO="$ECL/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
[ -d "$ECL/plugins" ] || { echo "No plugins folder in $ECL"; exit 1; }

for b in org.eclipse.core.resources:3.24.100 org.eclipse.core.jobs:3.15.900; do
  id=${b%%:*}; ver=${b##*:}
  # the jar that Eclipse really uses
  rel=$(grep "^$id,$ver\." "$INFO" | cut -d, -f3 || true)
  if [ -z "$rel" ]; then
    echo "Eclipse does not use $id $ver. This patch is for Eclipse 4.41 only:"
    grep "^$id," "$INFO" || true
    exit 1
  fi
  jar="$ECL/$rel"; name=$(basename "$jar")
  if [ "$ACTION" = restore ]; then
    cp "$BACKUP/$name" "$jar"; echo "Restored $name"; continue
  fi
  mkdir -p "$BACKUP"
  [ -f "$BACKUP/$name" ] || cp "$jar" "$BACKUP/$name"
  cp "$BACKUP/$name" "$jar"                    # always start from the original
  zip -q -d "$jar" 'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/*.DSA' >/dev/null 2>&1 || true
  (cd "$HERE/classes/$id" && zip -q -r "$jar" .)
  echo "Patched $name (original in $BACKUP)"
done
echo "Now start Eclipse once with -clean (or add -clean to the top of eclipse.ini for one start)."
