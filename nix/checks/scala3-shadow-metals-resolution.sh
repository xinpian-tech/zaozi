#!/usr/bin/env bash
# AC-4 Metals/PC gate: drive the real Metals server headless, in a minimal Scala 3.8.3 Mill
# workspace, against an ISOLATED writable cache, and request a textDocument/completion. With
# -Dzaozi.shadow.marker=true the patched presentation compiler injects a __zaozi_marker__
# completion and prints its loaded jar's CodeSource as `zaozi-shadow-pc <path>`, so a
# returned __zaozi_marker__ + the loaded PC jar hash prove Metals loaded the patched PC from
# the isolated cache. Runs the patched/empty/stock cache-state matrix.
#
#   scala3-shadow-metals-resolution.sh --metals DIR --shadow-cache DIR --shadow-jars DIR \
#       --extra-cache DIR --stock-pc JAR --probe FILE
#
# Needs on PATH: bash, java, mill, python3, jq, sha256sum, find, tar.
set -euo pipefail

VER=3.8.3
# --metals-cache is the Metals-ready isolated cache (shadow cache + Metals PC-setup closure,
# coherently resolved); --shadow-jars provides hashes.json for the patched PC hash.
METALS=; MC=; SJ=; STOCK_PC=; PROBE=; MILLDIR=
while [ $# -gt 0 ]; do
  case "$1" in
    --metals)       METALS="$2"; shift 2 ;;
    --metals-cache) MC="$2"; shift 2 ;;
    --shadow-jars)  SJ="$2"; shift 2 ;;
    --stock-pc)     STOCK_PC="$2"; shift 2 ;;
    --probe)        PROBE="$2"; shift 2 ;;
    --mill)         MILLDIR="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
for v in METALS MC SJ STOCK_PC PROBE MILLDIR; do [ -n "${!v}" ] || { echo "missing arg $v" >&2; exit 2; }; done
MILL_BIN="$MILLDIR/bin"

PASS=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }

PC_COORD="https/repo1.maven.org/maven2/org/scala-lang/scala3-presentation-compiler_3/$VER/scala3-presentation-compiler_3-$VER.jar"
PC_WANT=$(jq -r '.artifacts."scala3-presentation-compiler_3".jarSha256' "$SJ/share/zaozi-shadow/hashes.json")
[ -n "$PC_WANT" ] && [ "$PC_WANT" != null ] || fail "could not read published patched PC hash"
STOCK_PC_SHA=$(sha "$STOCK_PC")
[ "$STOCK_PC_SHA" != "$PC_WANT" ] || fail "pinned stock PC hash equals patched?!"

BASE=$(mktemp -d)
trap 'chmod -R u+w "$BASE" 2>/dev/null; rm -rf "$BASE" 2>/dev/null || true' EXIT

# Drive headless Metals against an isolated copy of cacheSrcDir (a dir whose child is
# https/), merging the Metals extra closure in. Writes completion labels to $2 and Metals'
# server log to $3. If $4 is given, the patched PC writes the loaded PC jar URL there
# (-Dzaozi.shadow.pc.provenance). Returns the probe exit code.
run_metals() { # $1 cacheSrcDir, $2 labelsOut, $3 metalsLog, $4 provenanceOut (optional)
  local R WS NCD CC H X
  R=$(mktemp -d -p "$BASE"); WS="$R/ws"; mkdir -p "$WS/foo/src/demo"
  cat > "$WS/build.mill" <<'M'
//| mill-version: 1.1.2
package build
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "3.8.3"
}
M
  printf 'package demo\nobject Main:\n  val greeting: String = "hi"\n  def run(): Unit =\n    greeting.\n' > "$WS/foo/src/demo/Main.scala"
  NCD="$R/coursier"; CC="$NCD/cache"; H="$R/home"; X="$R/xdg"; mkdir -p "$NCD" "$H" "$X"
  cp -rL "$1" "$CC"; chmod -R u+w "$CC"
  local JOPTS="-Dcoursier.cache=$CC -Dcoursier.ivy.home=$NCD -Divy.home=$NCD -Duser.home=$H -Dzaozi.shadow.marker=true"
  JOPTS="$JOPTS -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=1 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=1"
  if [ -n "${4:-}" ]; then JOPTS="$JOPTS -Dzaozi.shadow.pc.provenance=$4"; fi
  printf '%s\n' $JOPTS > "$R/mopts"
  (
    cd "$WS"
    export COURSIER_CACHE="$CC" HOME="$H" XDG_CACHE_HOME="$X" IVY_HOME="$NCD" \
           MILL_JVM_OPTS_PATH="$R/mopts" JAVA_TOOL_OPTIONS="$JOPTS" \
           METALS_BIN="$METALS/bin/metals" METALS_STDERR="$3" PROBE_TIMEOUT=540 \
           PATH="$MILL_BIN:$PATH"
    unset SBT_OPTS JAVA_OPTS http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
    # Fail closed if the Mill BSP connection cannot be installed offline.
    if ! mill --no-daemon --offline mill.bsp.BSP/install >/dev/null 2>&1; then exit 90; fi
    [ -f "$WS/.bsp/mill-bsp.json" ] || exit 91
    python3 "$PROBE" "$WS" "$WS/foo/src/demo/Main.scala" 4 13
  ) >"$2" 2>>"$3"
}

echo "== 1. patched cache: headless Metals completion returns __zaozi_marker__ + JVM PC provenance =="
rc=0; run_metals "$MC/cache" "$BASE/p.labels" "$BASE/p.log" "$BASE/p.prov" || rc=$?
if ! grep -q "__zaozi_marker__" "$BASE/p.labels"; then
  echo "--- patched run failed (rc=$rc). labels: $(cat "$BASE/p.labels" 2>/dev/null) ---" >&2
  echo "--- metals.log tail ---" >&2; tail -25 "$BASE/p.log" >&2
  echo "--- workspace .metals/metals.log ---" >&2
  find "$BASE" -path '*/.metals/metals.log' -exec tail -20 {} \; 2>/dev/null >&2
  fail "patched: completion did not return __zaozi_marker__"
fi
ok "patched cache: headless Metals textDocument/completion returns __zaozi_marker__"
# PC provenance, fail-closed and JVM-side. The patched PC, running inside the Metals JVM, wrote
# the URL of the jar it was loaded from to $BASE/p.prov (-Dzaozi.shadow.pc.provenance) — a
# getResource/CodeSource URL of the form `jar:file:/<jar>!/<entry>` or `file:/<jar>`. Normalise
# to the local jar path, require it to exist, and require its hash == the published patched PC
# hash. There is NO cache-file fallback: if the Metals JVM does not report a hashable PC jar,
# the gate fails.
[ -s "$BASE/p.prov" ] || fail "patched: Metals JVM did not report a loaded PC jar path (provenance file empty)"
pcurl=$(cat "$BASE/p.prov")
pcloc=${pcurl#jar:}        # jar:file:/x!/...  -> file:/x!/...
pcloc=${pcloc%%!/*}        # file:/x!/...      -> file:/x
pcloc=${pcloc#file:}       # file:/x           -> /x  (or //x for file://x)
pcloc=${pcloc#//}          # //x               -> x
case "$pcloc" in /*) ;; *) pcloc="/$pcloc" ;; esac
[ -f "$pcloc" ] || fail "patched: JVM-reported PC jar path does not exist ($pcurl)"
[ "$(sha "$pcloc")" = "$PC_WANT" ] || fail "patched: JVM-loaded PC jar hash != published patched ($pcloc)"
ok "patched cache: the Metals JVM reports loading the PC jar whose hash == published patched"

echo "== 2. empty cache: offline Metals PC resolution fails, no marker =="
EMPTY="$BASE/empty"; mkdir -p "$EMPTY/https"
rc=0; run_metals "$EMPTY" "$BASE/e.labels" "$BASE/e.log" || rc=$?
[ "$rc" -ne 0 ] || fail "empty isolated cache: Metals/Mill unexpectedly succeeded offline"
if grep -q "__zaozi_marker__" "$BASE/e.labels" 2>/dev/null; then fail "empty cache unexpectedly returned the marker"; fi
ok "empty isolated cache: offline resolution fails (rc=$rc), no __zaozi_marker__ (no fallback)"

echo "== 3. stock cache: stock PC actually runs, returns ordinary completions but no marker =="
STOCK="$BASE/stockcache"; mkdir -p "$STOCK"; cp -rL "$MC/cache" "$STOCK/cache"; chmod -R u+w "$STOCK"
install -m644 "$STOCK_PC" "$STOCK/cache/$PC_COORD"
[ "$(sha "$STOCK/cache/$PC_COORD")" = "$STOCK_PC_SHA" ] || fail "stock overlay failed (pre-run hash != pinned stock)"
rc=0; run_metals "$STOCK/cache" "$BASE/s.labels" "$BASE/s.log" || rc=$?
# Fail-closed: the stock PC must genuinely start and serve completions (rc 0 + >=1 ordinary
# completion label), otherwise "no marker" would pass on a broken run that never reached the PC.
if [ "$rc" -ne 0 ]; then
  echo "--- stock run failed (rc=$rc). labels: $(cat "$BASE/s.labels" 2>/dev/null) ---" >&2
  tail -25 "$BASE/s.log" >&2
  fail "stock: headless Metals did not complete successfully (the stock PC must actually run)"
fi
nlabels=$(jq 'length' "$BASE/s.labels" 2>/dev/null || echo 0)
[ "${nlabels:-0}" -ge 1 ] || fail "stock: PC produced no ordinary completions (got: $(cat "$BASE/s.labels" 2>/dev/null))"
if grep -q "__zaozi_marker__" "$BASE/s.labels" 2>/dev/null; then fail "stock PC unexpectedly returned the marker"; fi
# The PC coordinate Metals resolved+loaded from is the pinned stock jar, before and after.
[ "$(sha "$STOCK/cache/$PC_COORD")" = "$STOCK_PC_SHA" ] || fail "stock: PC coordinate hash changed during the run"
ok "stock isolated cache: stock PC ran ($nlabels completions), no __zaozi_marker__, PC coord == pinned stock"

echo ""
echo "ALL $PASS CHECKS PASSED"
