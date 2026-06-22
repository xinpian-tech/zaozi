#!/usr/bin/env bash
# task4 gate: load the pinned Scala 3.8.4 dotty sbt build under nix and list its
# projects (`sbt projects`). This is the prerequisite to building the patched
# same-version compiler/PC jars from source. Reproducible + isolated.
#
#   nix shell nixpkgs#sbt nixpkgs#jdk21 -c bash nix/checks/scala3-build-load.sh [scala3-src-dir]
#
# Env (optional): PXY_HOST / PXY_PORT for an http(s) proxy (sandbox networks).
set -uo pipefail
SRC="${1:-/tmp/scala3-pc}"          # default to a local 3.8.4 checkout; CI passes the flake input
ROOT="$(mktemp -d)"
export HOME="$ROOT/home"
export COURSIER_CACHE="$ROOT/coursier"
export XDG_CACHE_HOME="$ROOT/xdg"
mkdir -p "$HOME" "$COURSIER_CACHE" "$XDG_CACHE_HOME"

PXY_HOST="${PXY_HOST:-}"; PXY_PORT="${PXY_PORT:-}"
PROXY_OPTS=""
[ -n "$PXY_HOST" ] && PROXY_OPTS="-Dhttp.proxyHost=$PXY_HOST -Dhttp.proxyPort=$PXY_PORT -Dhttps.proxyHost=$PXY_HOST -Dhttps.proxyPort=$PXY_PORT"
export SBT_OPTS="$PROXY_OPTS -Dsbt.server=false -Dsbt.color=false -Dsbt.ci=true"
export JAVA_TOOL_OPTIONS="$PROXY_OPTS"

echo "src: $SRC"; echo "sbt: $(sbt --numeric-version 2>/dev/null | tail -1)"; echo "java: $(java -version 2>&1 | grep -iv 'picked up' | head -1)"
echo "build.properties sbt.version: $(grep sbt.version "$SRC/project/build.properties" 2>/dev/null)"
WORK="$ROOT/src"; cp -r "$SRC" "$WORK" 2>/dev/null; chmod -R u+w "$WORK"
# NOTE: do NOT delete project/project — its build.sbt wires project/Dependencies.scala
# into the meta-build (dotty's recursive sbt layout).
# dotty's VersionUtil reads the git hash/commit-date via jgit; the flake-pinned source
# has no .git, so make the working copy a git repo (else: "One of setGitDir or
# setWorkTree must be called").
( cd "$WORK"
  git init -q
  git add -A 2>/dev/null
  git -c user.email=build@nix.local -c user.name=nix commit -q -m "scala3 build snapshot" 2>/dev/null
) || true
cd "$WORK"

echo "=== sbt projects (full log -> $ROOT/sbt.log) ==="
sbt -batch -no-colors "projects" > "$ROOT/sbt.log" 2>&1
rc=$?
tail -50 "$ROOT/sbt.log"
echo "FULL_LOG: $ROOT/sbt.log ; sbt exit: $rc"
if [ "$rc" -eq 0 ] \
   && ! grep -q "Project loading failed" "$ROOT/sbt.log" \
   && grep -qE "^\[info\][[:space:]]+(\* )?scala3-presentation-compiler$" "$ROOT/sbt.log" \
   && grep -qE "^\[info\][[:space:]]+(\* )?scala3-compiler-bootstrapped$" "$ROOT/sbt.log"; then
  echo "RESULT: PASS (build loaded; scala3-presentation-compiler + scala3-compiler-bootstrapped projects listed)"; exit 0
else
  echo "RESULT: FAIL (build did not load)"; exit 1
fi
