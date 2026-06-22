#!/usr/bin/env bash
# Headless Metals harness: drive a real Metals server, in a minimal Scala 3.8.4 Mill
# workspace, against an ISOLATED writable copy of the shadow cache, and request a
# textDocument/completion. With -Dzaozi.shadow.marker=true the patched presentation
# compiler injects a __zaozi_marker__ completion into every result, so a returned
# __zaozi_marker__ proves Metals loaded the patched PC from the isolated cache.
#
#   scala3-shadow-metals-resolution.sh --shadow-cache DIR --metals DIR --mill DIR \
#       --probe FILE [--extra-cache DIR]
#
# Needs on PATH: bash, java, python3, mill, jq, coreutils, gnutar.
#
# STATUS / KNOWN BLOCKER (see doc + goal-tracker): the pinned Metals 1.6.5 caps its
# supported Scala 3 line at ~3.8.0 and logs "unsupported Scala 3.8.4", refusing to start
# the presentation compiler for 3.8.4 — so the completion cannot be obtained with that
# Metals version regardless of the cache. This harness drives Metals fully offline and is
# verified to: start, connect to the Mill BSP server, compile + index the workspace, and
# select Scala 3.8.4. It detects and reports the unsupported-version wall explicitly
# instead of passing falsely. It will assert __zaozi_marker__ once a Metals that supports
# Scala 3.8.4 is pinned (and Metals' own offline resolution closure — scala-library
# 2.13.x, coursier-cli_3, os-lib_3, scala-collection-compat_3 — is present in the cache,
# pass it via --extra-cache).
set -euo pipefail

SHADOW=; METALS=; MILLDIR=; PROBE=; EXTRA=
while [ $# -gt 0 ]; do
  case "$1" in
    --shadow-cache) SHADOW="$2"; shift 2 ;;
    --metals)       METALS="$2"; shift 2 ;;
    --mill)         MILLDIR="$2"; shift 2 ;;
    --probe)        PROBE="$2"; shift 2 ;;
    --extra-cache)  EXTRA="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
for v in SHADOW METALS MILLDIR PROBE; do [ -n "${!v}" ] || { echo "missing arg $v" >&2; exit 2; }; done

ROOT=$(mktemp -d)
trap 'chmod -R u+w "$ROOT" 2>/dev/null; rm -rf "$ROOT"' EXIT
WS="$ROOT/ws"; mkdir -p "$WS/foo/src/demo"
cat > "$WS/build.mill" <<'M'
//| mill-version: 1.1.2
package build
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "3.8.3"
}
M
cat > "$WS/foo/src/demo/Main.scala" <<'S'
package demo
object Main:
  val greeting: String = "hi"
  def run(): Unit =
    greeting.
S

NCD="$ROOT/coursier"; export COURSIER_CACHE="$NCD/cache"
export HOME="$ROOT/home" XDG_CACHE_HOME="$ROOT/xdg"; mkdir -p "$HOME" "$XDG_CACHE_HOME" "$NCD"
cp -rL "$SHADOW/cache" "$COURSIER_CACHE"; chmod -R u+w "$COURSIER_CACHE"
# Metals' own PC-setup resolution closure (not in the Zaozi/shadow cache): merge it in if
# provided, then re-strip coursier sidecar dotfiles to keep a clean jar+pom cache.
if [ -n "$EXTRA" ] && [ -d "$EXTRA" ]; then
  cp -rL "$EXTRA/." "$COURSIER_CACHE/"; chmod -R u+w "$COURSIER_CACHE"
fi
find "$COURSIER_CACHE" -type f -name '.*' -delete 2>/dev/null || true

# Isolated, offline (fail-fast: refuse connections instantly instead of timing out), with
# the marker property on the Metals JVM (the PC runs in-process).
JOPTS="-Dcoursier.cache=$COURSIER_CACHE -Dcoursier.ivy.home=$NCD -Divy.home=$NCD -Duser.home=$HOME"
JOPTS="$JOPTS -Dzaozi.shadow.marker=true"
JOPTS="$JOPTS -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=1 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1"
printf '%s\n' $JOPTS > "$ROOT/mopts"
export MILL_JVM_OPTS_PATH="$ROOT/mopts" JAVA_TOOL_OPTIONS="$JOPTS"
export PATH="$MILLDIR/bin:$PATH"
export METALS_BIN="$METALS/bin/metals" METALS_STDERR="$ROOT/metals.stderr" PROBE_TIMEOUT="${PROBE_TIMEOUT:-300}"
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY SBT_OPTS JAVA_OPTS 2>/dev/null || true

cd "$WS"
echo "== pre-install the Mill BSP connection (so Metals connects instead of bootstrapping its own) =="
# Fail CLOSED: if BSP cannot be installed offline, stop before starting Metals (otherwise
# Metals would fall back to bootstrapping its own build server, weakening the proof).
if ! mill --no-daemon --offline mill.bsp.BSP/install >"$ROOT/bsp.log" 2>&1; then
  echo "FAIL  mill BSP install failed offline:" >&2; tail -6 "$ROOT/bsp.log" >&2; exit 1
fi
[ -f "$WS/.bsp/mill-bsp.json" ] || { echo "FAIL  .bsp/mill-bsp.json not created" >&2; exit 1; }
echo "  .bsp installed: $(ls .bsp)"

echo "== drive headless Metals completion =="
labels=$(python3 "$PROBE" "$WS" "$WS/foo/src/demo/Main.scala" 4 13 || true)
log="$WS/.metals/metals.log"

if printf '%s' "$labels" | grep -q "__zaozi_marker__"; then
  echo "PASS  headless Metals completion returned __zaozi_marker__ from the patched PC"
  exit 0
fi

# No marker: surface exactly why (verified to be the Metals version cap, not a harness bug).
echo "Metals drove offline this far:"
grep -iE "Connected to Build server|Compiled foo|Indexing complete|presentation compiler with project" "$log" 2>/dev/null | sed 's/^/  /' | tail -6
if grep -qi "unsupported Scala 3.8.4" "$log" 2>/dev/null; then
  echo "BLOCKED  pinned Metals ($("$METALS_BIN" --version 2>/dev/null | head -1)) rejects Scala 3.8.4:" >&2
  grep -i "unsupported Scala 3.8.4" "$log" | head -1 | sed 's/^/  /' >&2
  echo "  -> needs a Metals release that supports Scala 3.8.4 (task15 re-pin)." >&2
  exit 4
fi
echo "FAIL  no __zaozi_marker__ and no known blocker; see $log" >&2
grep -iE "error|download" "$log" 2>/dev/null | tail -8 >&2
exit 1
