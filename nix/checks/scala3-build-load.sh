#!/usr/bin/env bash
# task4 gate: load the pinned Scala 3.8.4 dotty sbt build under nix and list its
# projects (`sbt projects`). This is the prerequisite to building the patched
# same-version compiler/PC jars from source. Reproducible + isolated.
#
# Prefer the flake's pinned tools (this flake's nixpkgs) over ambient registry:
#   nix develop -c bash nix/checks/scala3-build-load.sh        # uses inputs.scala3-src
#   nix shell .#legacyPackages.x86_64-linux.{sbt,jdk21,git} -c bash nix/checks/scala3-build-load.sh
# The flake `checks.scala3-build-load` output wires this with the pinned toolchain.
#
# Env (optional): PXY_HOST / PXY_PORT for an http(s) proxy (sandbox networks).
set -euo pipefail
# Default source = the flake-pinned scala3-src (NOT an ad-hoc /tmp checkout, which can
# be incomplete and produce a false "not found: value Build" failure).
SRC="${1:-$(nix eval --raw '.#inputs.scala3-src.outPath' 2>/dev/null || true)}"
if [ -z "${SRC:-}" ] || [ ! -f "$SRC/project/Build.scala" ] || [ ! -f "$SRC/project/build.properties" ]; then
  echo "ERROR: invalid scala3 source (need a complete checkout with project/Build.scala): '${SRC:-<empty>}'" >&2
  echo "Pass the path explicitly, or run from the flake so 'nix eval .#inputs.scala3-src' resolves." >&2
  exit 2
fi
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
WORK="$ROOT/src"
cp -r "$SRC" "$WORK"            # fail-fast under set -e if the source is unreadable
chmod -R u+w "$WORK"
# NOTE: do NOT delete project/project — its build.sbt wires project/Dependencies.scala
# into the meta-build (dotty's recursive sbt layout).
# dotty's VersionUtil reads the git hash/commit-date via jgit; the flake-pinned source
# has no .git, so make the working copy a git repo (else: "One of setGitDir or
# setWorkTree must be called").
( cd "$WORK"
  git init -q
  git add -A
  git -c user.email=build@nix.local -c user.name=nix commit -q -m "scala3 build snapshot"
) >/dev/null 2>&1 || echo "WARN: git snapshot setup failed; VersionUtil/jgit may error" >&2
cd "$WORK"

echo "=== sbt projects (full log -> $ROOT/sbt.log) ==="
rc=0
sbt -batch -no-colors "projects" > "$ROOT/sbt.log" 2>&1 || rc=$?   # don't let set -e abort before we report
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
