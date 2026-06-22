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
# (-Dzaozi.shadow.pc.provenance). $5/$6/$7 override the fixture source and completion
# line/char (default: the greeting fixture at 4 13). Returns the probe exit code.
run_metals() { # $1 cacheSrcDir, $2 out, $3 metalsLog, $4 provenanceOut, $5 mainSrc, $6 line, $7 char, $8 mode
  local R WS NCD CC H X LN CH
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
  if [ -n "${5:-}" ]; then printf '%s' "$5" > "$WS/foo/src/demo/Main.scala"
  else printf 'package demo\nobject Main:\n  val greeting: String = "hi"\n  def run(): Unit =\n    greeting.\n' > "$WS/foo/src/demo/Main.scala"; fi
  LN="${6:-4}"; CH="${7:-13}"
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
    if [ "${8:-}" = "batch" ]; then
      # $9 is a space-separated list of name:mode:line:char specs (no spaces within a spec).
      python3 "$PROBE" "$WS" "$WS/foo/src/demo/Main.scala" BATCH ${9}
    else
      python3 "$PROBE" "$WS" "$WS/foo/src/demo/Main.scala" "$LN" "$CH" "${8:-completion}"
    fi
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

echo "== 4. patched cache: zaozi Bundle/ProbeBundle field completion (AC-5 matrix) =="
# A synthetic fixture that declares the minimal zaozi shapes BY their real fully-qualified
# names (the resolver matches by FQN, so no real zaozi/CIRCT closure is needed). It covers a
# Bundle (`io`), a ProbeBundle (`pb`), a typed prefix (`io.a`), and a non-zaozi Dynamic (`d`),
# plus a private field `p` and a non-BundleField val `n` that must NOT be offered.
ZFIX=$(cat <<'SCALA'
package me.jiuyang.zaozi.valuetpe {
  trait Data
  class Bits extends Data
  case class BundleField[T <: Data](dataType: T)
  trait Bundle extends Data with me.jiuyang.zaozi.magic.DynamicSubfield
  trait ProbeBundle extends Data with me.jiuyang.zaozi.magic.DynamicSubfield
}
package me.jiuyang.zaozi.magic {
  trait DynamicSubfield
}
package me.jiuyang.zaozi.reftpe {
  trait Referable[T <: me.jiuyang.zaozi.valuetpe.Data] extends scala.Dynamic {
    def selectDynamic(name: String): Any
  }
}
package demo {
  import scala.language.dynamics
  import me.jiuyang.zaozi.reftpe.Referable
  import me.jiuyang.zaozi.valuetpe.{Bundle, ProbeBundle, Bits, BundleField}
  class MyBundle extends Bundle {
    val a: BundleField[Bits] = ???
    val b: BundleField[Bits] = ???
    val k: Option[BundleField[Bits]] = ???
    private val p: BundleField[Bits] = ???
    val n: Int = 0
  }
  class MyProbe extends ProbeBundle {
    val x: BundleField[Bits] = ???
  }
  class PlainDyn extends scala.Dynamic {
    def selectDynamic(n: String): Any = ???
  }
  object Main {
    def run(io: Referable[MyBundle]): Unit =
      io.
    def pre(io: Referable[MyBundle]): Unit =
      io.a
    def prb(pb: Referable[MyProbe]): Unit =
      pb.
    def run2(d: PlainDyn): Unit =
      d.
  }
}
SCALA
)
# Run Metals at the (unique, full-line) caret matched by regex $2 against the fixture in $ZSRC.
# $1 out file, $3 mode (completion|hover|definition, default completion), $4 char override.
zrun() {
  local n t ln ch rc=0
  n=$(grep -nxE "$2" <<<"$ZSRC" | head -1 | cut -d: -f1)
  [ -n "$n" ] || fail "zaozi: could not locate caret /$2/ in the fixture"
  t=$(sed -n "${n}p" <<<"$ZSRC"); ln=$((n-1)); ch=${4:-${#t}}
  run_metals "$MC/cache" "$1" "${1%.*}.log" "" "$ZSRC" "$ln" "$ch" "${3:-completion}" || rc=$?
  [ "$rc" -eq 0 ] || { tail -20 "${1%.*}.log" >&2; fail "zaozi: ${3:-completion} run failed (rc=$rc) at /$2/"; }
}
has() { jq -e --arg f "$1" 'any(.[]; startswith($f))' "$2" >/dev/null 2>&1; } # any label starts with $1
ZSRC="$ZFIX"
# 4a. io. : public BundleField fields a,b,k with Ref[E] detail; private p and non-field n absent.
zrun "$BASE/z.labels" '      io\.'
has 'a: Ref['        "$BASE/z.labels" || fail "zaozi: io. missing 'a: Ref[..]' (got: $(cat "$BASE/z.labels"))"
has 'b: Ref['        "$BASE/z.labels" || fail "zaozi: io. missing 'b: Ref[..]'"
has 'k: Option[Ref[' "$BASE/z.labels" || fail "zaozi: io. missing 'k: Option[Ref[..]]'"
if has 'p: ' "$BASE/z.labels"; then fail "zaozi: io. wrongly offered private field 'p'"; fi
if has 'n: ' "$BASE/z.labels"; then fail "zaozi: io. wrongly offered non-BundleField val 'n'"; fi
for f in a b k; do
  c=$(jq --arg f "$f" '[.[] | select(startswith($f + ": "))] | length' "$BASE/z.labels")
  [ "$c" = "1" ] || fail "zaozi: field '$f' appears $c times in io. completion (expected 1; no duplicates)"
done
ok "patched cache: io. lists a,b,k as Ref[E]/Option[Ref[E]]; private+non-field excluded; no dups"
# 4b. io.a : typed prefix yields only field a.
zrun "$BASE/zp.labels" '      io\.a'
has 'a: Ref[' "$BASE/zp.labels" || fail "zaozi: prefix io.a@@ missing 'a' (got: $(cat "$BASE/zp.labels"))"
if has 'b: ' "$BASE/zp.labels"; then fail "zaozi: prefix io.a@@ wrongly offered 'b'"; fi
if has 'k: ' "$BASE/zp.labels"; then fail "zaozi: prefix io.a@@ wrongly offered 'k'"; fi
ok "patched cache: typed prefix io.a@@ yields only field a"
# 4c. pb. : ProbeBundle fields are listed too.
zrun "$BASE/zb.labels" '      pb\.'
has 'x: Ref[' "$BASE/zb.labels" || fail "zaozi: ProbeBundle pb. missing 'x' (got: $(cat "$BASE/zb.labels"))"
ok "patched cache: ProbeBundle completion at pb. lists field x"
# 4d. d. : a non-zaozi scala.Dynamic qualifier is NOT augmented.
zrun "$BASE/zn.labels" '      d\.'
for bad in a b k x; do
  if has "$bad: Ref" "$BASE/zn.labels"; then fail "zaozi: non-zaozi Dynamic wrongly augmented with '$bad'"; fi
done
ok "patched cache: a non-zaozi scala.Dynamic qualifier is not augmented (type-gated)"

echo "== 5. patched cache: zaozi hover + go-to-definition matrix (AC-6/AC-7) =="
# A COMPLETE fixture (no incomplete `io.`) whose Referable.selectDynamic is a transparent inline
# returning Ref[Bits], so `io.a` becomes a genuine macro `Inlined(io.selectDynamic("a"), ...)` at
# the PC's typer level — exactly the shape the hover/definition hook targets in real zaozi. It
# covers a Bundle (`io`), a ProbeBundle (`pb`), an unknown field (`io.zzz`), and an ordinary
# non-zaozi val (`s`).
ZHOV=$(cat <<'SCALA'
package me.jiuyang.zaozi.valuetpe {
  trait Data
  class Bits extends Data
  case class BundleField[T <: Data](dataType: T)
  trait Bundle extends Data with me.jiuyang.zaozi.magic.DynamicSubfield
  trait ProbeBundle extends Data with me.jiuyang.zaozi.magic.DynamicSubfield
}
package me.jiuyang.zaozi.magic {
  trait DynamicSubfield
}
package me.jiuyang.zaozi.reftpe {
  class Ref[E]
  trait Referable[T <: me.jiuyang.zaozi.valuetpe.Data] extends scala.Dynamic {
    transparent inline def selectDynamic(name: String): Any =
      new me.jiuyang.zaozi.reftpe.Ref[me.jiuyang.zaozi.valuetpe.Bits]
  }
}
package demo {
  import scala.language.dynamics
  import me.jiuyang.zaozi.reftpe.{Referable, Ref}
  import me.jiuyang.zaozi.valuetpe.{Bundle, ProbeBundle, Bits, BundleField}
  class MyBundle extends Bundle {
    val a: BundleField[Bits] = ???
    val b: BundleField[Bits] = ???
  }
  class MyProbe extends ProbeBundle {
    val x: BundleField[Bits] = ???
  }
  object Main {
    val s: String = "hi"
    def pre(io: Referable[MyBundle]): Ref[Bits] =
      io.a
    def neg(io: Referable[MyBundle]): Ref[Bits] =
      io.zzz
    def prb(pb: Referable[MyProbe]): Ref[Bits] =
      pb.x
    def ord: String =
      s
  }
}
SCALA
)
# caret regex -> "lspLine:char" (char = end of line, just past the identifier).
caretpos() {
  local n t; n=$(grep -nxE "$1" <<<"$ZHOV" | head -1 | cut -d: -f1)
  [ -n "$n" ] || fail "zaozi: could not locate caret /$1/ in the hover fixture"
  t=$(sed -n "${n}p" <<<"$ZHOV"); echo "$((n-1)):${#t}"
}
defline() { # caret regex of a `val` decl -> its 0-indexed line
  local n; n=$(grep -nxE "$1" <<<"$ZHOV" | head -1 | cut -d: -f1)
  [ -n "$n" ] || fail "zaozi: could not locate '$1' in the hover fixture"; echo $((n-1))
}
IOA=$(caretpos '      io\.a'); IOZ=$(caretpos '      io\.zzz'); PBX=$(caretpos '      pb\.x'); SS=$(caretpos '      s')
VALA_LN=$(defline '    val a: BundleField\[Bits\] = \?\?\?')
VALX_LN=$(defline '    val x: BundleField\[Bits\] = \?\?\?')
# One Metals session, many requests (name:mode:line:char); io.a is first so readiness polls there.
SPECS="hovA:hover:$IOA defA:definition:$IOA hovZ:hover:$IOZ defZ:definition:$IOZ hovX:hover:$PBX defX:definition:$PBX hovS:hover:$SS"
rc=0; run_metals "$MC/cache" "$BASE/b.json" "$BASE/b.log" "" "$ZHOV" "" "" batch "$SPECS" || rc=$?
[ "$rc" -eq 0 ] || { tail -25 "$BASE/b.log" >&2; fail "zaozi: hover/definition batch run failed (rc=$rc)"; }
B="$BASE/b.json"
jq -e . "$B" >/dev/null 2>&1 || { cat "$B" >&2; fail "zaozi: batch output is not valid JSON"; }
hov() { jq -r --arg k "$1" '.[$k] // ""' "$B"; }
# 5a. hover io.a shows the field `val a` with a Ref[Bits] type, not selectDynamic.
ha=$(hov hovA)
grep -q 'Ref\[Bits\]' <<<"$ha" || fail "zaozi: hover io.a missing Ref[Bits] (got: $ha)"
grep -q 'val a'       <<<"$ha" || fail "zaozi: hover io.a missing the 'val a' field signature (got: $ha)"
if grep -q 'selectDynamic' <<<"$ha"; then fail "zaozi: hover io.a fell back to selectDynamic (got: $ha)"; fi
ok "patched cache: hover io.a shows 'val a' with Ref[Bits] (not selectDynamic)"
# 5b. definition io.a -> the bundle's val a.
[ "$(jq -r '.defA[0].line // empty' "$B")" = "$VALA_LN" ] || fail "zaozi: definition io.a not at val a (line $VALA_LN): $(jq -c '.defA' "$B")"
ok "patched cache: go-to-definition io.a -> the bundle's val a (line $VALA_LN)"
# 5c. definition io.zzz (unknown field) -> empty (no selectDynamic, no bogus location).
[ "$(jq -r '.defZ | length' "$B")" = "0" ] || fail "zaozi: definition io.zzz returned a location (expected none): $(jq -c '.defZ' "$B")"
ok "patched cache: misspelled io.zzz -> empty definition (no selectDynamic / no bogus location)"
# 5d. hover io.zzz (unknown field) fabricates no field.
hz=$(hov hovZ)
if grep -qE 'Ref\[Bits\]|val a|val zzz' <<<"$hz"; then fail "zaozi: hover io.zzz fabricated a field (got: $hz)"; fi
ok "patched cache: hover on unknown io.zzz fabricates no field"
# 5e. ProbeBundle pb.x: hover (Ref[Bits] + val x) and definition -> val x.
hx=$(hov hovX)
grep -q 'Ref\[Bits\]' <<<"$hx" || fail "zaozi: ProbeBundle hover pb.x missing Ref[Bits] (got: $hx)"
grep -q 'val x'       <<<"$hx" || fail "zaozi: ProbeBundle hover pb.x missing 'val x' (got: $hx)"
[ "$(jq -r '.defX[0].line // empty' "$B")" = "$VALX_LN" ] || fail "zaozi: ProbeBundle definition pb.x not at val x (line $VALX_LN): $(jq -c '.defX' "$B")"
ok "patched cache: ProbeBundle pb.x hover (val x: Ref[Bits]) + definition -> val x"
# 5f. ordinary non-zaozi hover is unchanged.
hs=$(hov hovS)
grep -q 'String' <<<"$hs" || fail "zaozi: ordinary non-zaozi hover changed (expected String, got: $hs)"
ok "patched cache: ordinary non-zaozi hover is unchanged (shows String)"

echo ""
echo "ALL $PASS CHECKS PASSED"
