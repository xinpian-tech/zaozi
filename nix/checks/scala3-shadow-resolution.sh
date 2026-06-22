#!/usr/bin/env bash
# Prove the shadow cache is the sole authoritative source for the REAL Mill/coursier
# compiler resolution. For each cache state, point an isolated COURSIER_CACHE (plus
# isolated HOME/XDG/IVY_HOME and sanitized JVM/proxy opts) at a writable copy of that
# cache, then run `mill --no-daemon --offline show zaozi.scalaCompilerClasspath` in a clean
# workspace copy and inspect the actually-resolved classpath:
#   - patched cache -> Mill resolves the patched scala3-compiler_3:3.8.4 (path under the
#     isolated cache, SHA-256 == hashes.json); the loaded compiler emits the marker.
#   - empty cache   -> offline Mill resolution fails (no bytes, no fallback).
#   - stock cache   -> Mill resolves the stock compiler (hash != patched); no marker.
#
#   scala3-shadow-resolution.sh --shadow-cache DIR --shadow-jars DIR --stock-cache DIR \
#                               --workspace DIR
#
# Needs on PATH: mill, java, javac, jq, sha256sum, grep, tar, timeout.
set -euo pipefail

VER=3.8.4
SC=; SJ=; STOCK_SC=; STOCK_C=; WORKSPACE=
while [ $# -gt 0 ]; do
  case "$1" in
    --shadow-cache)   SC="$2"; shift 2 ;;
    --shadow-jars)    SJ="$2"; shift 2 ;;
    --stock-cache)    STOCK_SC="$2"; shift 2 ;;
    --stock-compiler) STOCK_C="$2"; shift 2 ;;
    --workspace)      WORKSPACE="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
for v in SC SJ STOCK_SC STOCK_C WORKSPACE; do [ -n "${!v}" ] || { echo "missing arg $v" >&2; exit 2; }; done

PASS=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }

BASE=$(mktemp -d)
trap 'chmod -R u+w "$BASE" 2>/dev/null; rm -rf "$BASE"' EXIT

want=$(jq -r '.artifacts."scala3-compiler_3".jarSha256' "$SJ/share/zaozi-shadow/hashes.json")
[ -n "$want" ] && [ "$want" != null ] || fail "could not read published patched compiler hash"

# Run the real consumer (Mill) against an isolated copy of $1 (a cache dir whose immediate
# child is https/). Writes the resolved-classpath JSON to $2 and Mill's log to $3, sets the
# global LAST_CC to the isolated COURSIER_CACHE, and returns Mill's exit code.
LAST_CC=""
mill_show() { # $1 cacheSrc, $2 outjson, $3 outerr -> mill rc
  local R WS NCD CC H X JOPTS rc
  R=$(mktemp -d -p "$BASE")
  WS="$R/ws"; mkdir -p "$WS"
  tar -C "$WORKSPACE" -cf - --exclude=./out . | tar -C "$WS" -xf -
  chmod -R u+w "$WS"
  NCD="$R/coursier"; CC="$NCD/cache"; H="$R/home"; X="$R/xdg"
  mkdir -p "$NCD" "$H" "$X"
  cp -rL "$1" "$CC"; chmod -R u+w "$CC"
  JOPTS="-Dcoursier.cache=$CC -Dcoursier.ivy.home=$NCD -Divy.home=$NCD -Duser.home=$H"
  printf '%s\n' $JOPTS > "$R/mopts"
  LAST_CC="$CC"
  rc=0
  (
    cd "$WS"
    export COURSIER_CACHE="$CC" HOME="$H" XDG_CACHE_HOME="$X" IVY_HOME="$NCD" \
           MILL_JVM_OPTS_PATH="$R/mopts" JAVA_TOOL_OPTIONS="$JOPTS"
    unset JAVA_OPTS SBT_OPTS http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true
    timeout 360 mill --no-daemon --offline show zaozi.scalaCompilerClasspath
  ) >"$2" 2>"$3" || rc=$?
  return $rc
}
# Mill renders classpath PathRefs as "qref:v1:<hash>:/abs/path"; the path is everything
# after the last ':' (nix/tmp paths contain no ':'), so bash '##*:' strips the prefix with
# no external sed dependency.
strip_qref() { local x; x="$1"; printf '%s' "${x##*:}"; }

# A probe that reports, from the actual JVM, where dotty.tools.dotc.Driver was loaded from.
mkdir -p "$BASE/probe"
cat > "$BASE/probe/Probe.java" <<'JAVA'
public class Probe {
  public static void main(String[] a) throws Exception {
    var loc = Class.forName("dotty.tools.dotc.Driver")
      .getProtectionDomain().getCodeSource().getLocation();
    System.out.println(loc.getPath());
  }
}
JAVA
javac -d "$BASE/probe" "$BASE/probe/Probe.java"

cat > "$BASE/F.scala" <<'SCALA'
package z
object F:
  val a: Int = 1
SCALA

echo "== 1. patched cache: real Mill resolution + JVM provenance + gated marker =="
mill_show "$SC/cache" "$BASE/p.json" "$BASE/p.err" || fail "mill failed to resolve against the patched cache: $(tail -3 "$BASE/p.err")"
PCACHE="$LAST_CC"
mapfile -t RAW < <(jq -r '.[]' "$BASE/p.json")
[ "${#RAW[@]}" -ge 8 ] || fail "patched: unexpected classpath size ${#RAW[@]}"
CP=(); for r in "${RAW[@]}"; do CP+=("$(strip_qref "$r")"); done
CJAR=""; for j in "${CP[@]}"; do [[ "$j" == *"scala3-compiler_3-$VER.jar" ]] && CJAR="$j"; done
[ -n "$CJAR" ] || fail "patched: no scala3-compiler_3 jar in Mill classpath"
case "$CJAR" in "$PCACHE"/*) ;; *) fail "patched: resolved compiler not under isolated cache: $CJAR";; esac
[ "$(sha "$CJAR")" = "$want" ] || fail "patched: Mill-resolved compiler hash != published patched"
ok "patched cache: Mill resolves the compiler under the isolated cache; hash == published patched"
CPJOIN=$(IFS=:; echo "${CP[*]}")
loc=$(java -cp "$BASE/probe:$CPJOIN" Probe 2>/dev/null)
case "$loc" in "$PCACHE"/*) ;; *) fail "patched: JVM loaded Driver from outside the isolated cache: $loc";; esac
[ "$(sha "$loc")" = "$want" ] || fail "patched: probed CodeSource hash != published patched"
ok "patched cache: the compiler JVM loads Driver from the Mill-resolved patched jar"
mkdir -p "$BASE/out1"
rc=0
java -Dzaozi.shadow.marker=true -cp "$CPJOIN" dotty.tools.dotc.Main -classpath "$CPJOIN" \
  -d "$BASE/out1" "$BASE/F.scala" >"$BASE/m1.out" 2>"$BASE/m1.err" || rc=$?
[ "$rc" -eq 0 ] || fail "patched: compile from the Mill-resolved classpath failed (rc=$rc): $(tail -3 "$BASE/m1.err")"
[ -f "$BASE/out1/z/F.class" ] || fail "patched: compile produced no class output"
grep -qF "zaozi-shadow-marker compiler org.scala-lang:scala3-compiler_3:$VER" "$BASE/m1.err" \
  || fail "patched: gated marker not emitted from the Mill-resolved compiler"
ok "patched cache: the Mill-resolved compiler compiles the fixture and emits the gated marker"

echo "== 2. empty cache: offline Mill resolution fails =="
EMPTY="$BASE/empty"; mkdir -p "$EMPTY/https"
rc=0; mill_show "$EMPTY" "$BASE/e.json" "$BASE/e.err" || rc=$?
[ "$rc" -ne 0 ] || fail "empty isolated cache: Mill unexpectedly succeeded offline"
ok "empty isolated cache: offline Mill resolution fails (rc=$rc, no fallback)"

echo "== 3. stock cache: exact stock bytes, no marker =="
stockhash=$(sha "$STOCK_C")
[ "$stockhash" != "$want" ] || fail "pinned stock compiler hash equals patched?!"
mill_show "$STOCK_SC/cache" "$BASE/s.json" "$BASE/s.err" || fail "mill failed to resolve against the stock cache: $(tail -3 "$BASE/s.err")"
KCACHE="$LAST_CC"
mapfile -t SRAW < <(jq -r '.[]' "$BASE/s.json")
SCP=(); for r in "${SRAW[@]}"; do SCP+=("$(strip_qref "$r")"); done
KJAR=""; for j in "${SCP[@]}"; do [[ "$j" == *"scala3-compiler_3-$VER.jar" ]] && KJAR="$j"; done
[ -n "$KJAR" ] || fail "stock: no scala3-compiler_3 jar in Mill classpath"
case "$KJAR" in "$KCACHE"/*) ;; *) fail "stock: resolved compiler not under isolated stock cache: $KJAR";; esac
[ "$(sha "$KJAR")" = "$stockhash" ] || fail "stock: Mill-resolved compiler hash != pinned stock jar"
ok "stock cache: Mill resolves exactly the pinned stock compiler (hash == stock, != patched)"
SCPJOIN=$(IFS=:; echo "${SCP[*]}")
mkdir -p "$BASE/out3"
rc=0
java -Dzaozi.shadow.marker=true -cp "$SCPJOIN" dotty.tools.dotc.Main -classpath "$SCPJOIN" \
  -d "$BASE/out3" "$BASE/F.scala" >"$BASE/m3.out" 2>"$BASE/m3.err" || rc=$?
[ "$rc" -eq 0 ] || fail "stock: compile from the Mill-resolved classpath failed (rc=$rc): $(tail -3 "$BASE/m3.err")"
[ -f "$BASE/out3/z/F.class" ] || fail "stock: compile produced no class output"
if grep -q "zaozi-shadow-marker" "$BASE/m3.err"; then fail "stock compiler emitted the marker?!"; fi
ok "stock cache: the Mill-resolved stock compiler compiles the fixture and emits no marker"

echo ""
echo "ALL $PASS CHECKS PASSED"
