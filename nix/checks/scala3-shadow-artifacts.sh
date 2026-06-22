#!/usr/bin/env bash
# Repeatable verification gate for the shadow toolchain artifacts. Asserts the
# patched-jar + shadow-cache contract and proves the marker patch is inert:
# the patched compiler, with zaozi.shadow.marker unset, produces compile output
# identical to the stock 3.8.4 compiler and prints no marker; with the property
# set it prints exactly the gated marker line.
#
#   scala3-shadow-artifacts.sh --shadow-jars DIR --shadow-cache DIR \
#                              --stock-compiler JAR --stock-pc JAR
#
# Needs on PATH: java, unzip, jq, sha256sum, cmp, find, diff.
set -euo pipefail

VER=3.8.3
SJ=; SC=; STOCK_C=; STOCK_PC=
while [ $# -gt 0 ]; do
  case "$1" in
    --shadow-jars)    SJ="$2"; shift 2 ;;
    --shadow-cache)   SC="$2"; shift 2 ;;
    --stock-compiler) STOCK_C="$2"; shift 2 ;;
    --stock-pc)       STOCK_PC="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
for v in SJ SC STOCK_C STOCK_PC; do
  [ -n "${!v}" ] || { echo "missing required arg for $v" >&2; exit 2; }
done

PASS=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }

JARS="$SJ/jars"
HASHES="$SJ/share/zaozi-shadow/hashes.json"
CJAR="$JARS/scala3-compiler_3-$VER.jar"
PJAR="$JARS/scala3-presentation-compiler_3-$VER.jar"
CMARK="META-INF/zaozi-shadow/org.scala-lang-scala3-compiler_3-$VER.properties"
PMARK="META-INF/zaozi-shadow/org.scala-lang-scala3-presentation-compiler_3-$VER.properties"
sha() { sha256sum "$1" | cut -d' ' -f1; }
entrysha() { unzip -p "$1" "$2" | sha256sum | cut -d' ' -f1; }
# Capture then match via here-string: `unzip -l | grep -q` would SIGPIPE unzip (grep -q
# closes the pipe early) and trip `set -o pipefail`.
has() { local out; out=$(unzip -l "$1" 2>/dev/null) || true; grep -qF "$2" <<<"$out"; }
# Exact zip-entry match (whole line) via the entry listing.
hasentry() { local out; out=$(unzip -Z1 "$1" 2>/dev/null) || true; grep -qFx "$2" <<<"$out"; }

echo "== A. patched jars =="
[ -f "$CJAR" ] || fail "compiler jar missing"
[ -f "$PJAR" ] || fail "PC jar missing"
ok "both patched jars exist"
# Exact entries (single-quoted so the '$' in the class name stays literal).
if hasentry "$CJAR" 'dotty/tools/dotc/semanticdb/ExtractSemanticDB$ExtractSemanticInfo.class'; then
  ok "compiler jar has ExtractSemanticInfo"
else fail "compiler jar missing ExtractSemanticInfo"; fi
if hasentry "$PJAR" 'dotty/tools/pc/ScalaPresentationCompiler.class'; then
  ok "PC jar has ScalaPresentationCompiler"
else fail "PC jar missing ScalaPresentationCompiler"; fi
for pair in "$CJAR=$CMARK" "$PJAR=$PMARK"; do
  jar="${pair%=*}"; mark="${pair#*=}"
  has "$jar" "$mark" || fail "marker resource missing in $(basename "$jar")"
  [ -n "$(unzip -p "$jar" "$mark")" ] || fail "marker resource empty in $(basename "$jar")"
done
ok "both marker resources present + non-empty"

echo "== B. hashes.json consistency =="
[ -f "$HASHES" ] || fail "hashes.json missing"
jq -e . "$HASHES" >/dev/null || fail "hashes.json does not parse"
check_hash() { # $1 artifact key, $2 jar, $3 marker path
  local jjar jmark ajar amark
  jjar=$(jq -r ".artifacts.\"$1\".jarSha256" "$HASHES")
  jmark=$(jq -r ".artifacts.\"$1\".markerSha256" "$HASHES")
  ajar=$(sha "$2"); amark=$(entrysha "$2" "$3")
  [ "$jjar" = "$ajar" ]   || fail "$1 jarSha256 mismatch ($jjar vs $ajar)"
  [ "$jmark" = "$amark" ] || fail "$1 markerSha256 mismatch ($jmark vs $amark)"
}
check_hash scala3-compiler_3 "$CJAR" "$CMARK"
check_hash scala3-presentation-compiler_3 "$PJAR" "$PMARK"
ok "hashes.json whole-jar + marker SHA-256 match actual bytes"

echo "== C. patched != stock =="
[ "$(sha "$CJAR")" != "$(sha "$STOCK_C")" ]  || fail "compiler jar equals stock"
[ "$(sha "$PJAR")" != "$(sha "$STOCK_PC")" ] || fail "PC jar equals stock"
# and stock jars carry no marker resource
if has "$STOCK_C" "META-INF/zaozi-shadow"; then fail "stock compiler unexpectedly has a marker"; fi
if has "$STOCK_PC" "META-INF/zaozi-shadow"; then fail "stock PC unexpectedly has a marker"; fi
ok "patched jars differ from stock; stock jars carry no marker"

echo "== D. shadow cache structure =="
CROOT="$SC/cache"
[ -d "$CROOT/https" ] || fail "cache root has no immediate https/ child"
[ "$(ls "$CROOT")" = "https" ] || fail "cache root child is not exactly https/"
M="$CROOT/https/repo1.maven.org/maven2/org/scala-lang"
for d in scala3-compiler_3 scala3-presentation-compiler_3; do
  dir="$M/$d/$VER"
  [ "$(find "$dir" -maxdepth 1 -type f -name '*.jar' | wc -l)" -eq 1 ] || fail "$d: not exactly 1 jar"
  [ "$(find "$dir" -maxdepth 1 -type f -name '*.pom' | wc -l)" -eq 1 ] || fail "$d: not exactly 1 pom"
done
cmp -s "$M/scala3-compiler_3/$VER/scala3-compiler_3-$VER.jar" "$CJAR" || fail "cache compiler jar != patched"
cmp -s "$M/scala3-presentation-compiler_3/$VER/scala3-presentation-compiler_3-$VER.jar" "$PJAR" || fail "cache PC jar != patched"
ok "cache: https/ root; 1 jar+1 pom per shadowed coord; patched bytes in place"
B2="$CROOT/https/repo1.maven.org/maven2"
# Every PC-closure coordinate absent from the Zaozi lock must be present, jar+pom, so the
# cache is an offline-resolvable authoritative source. All 11 are jar-bearing libraries.
for c in org/scalameta/mtags-interfaces/1.6.7 \
         org/lz4/lz4-java/1.8.0 \
         org/eclipse/lsp4j/org.eclipse.lsp4j/1.0.0 \
         org/eclipse/lsp4j/org.eclipse.lsp4j.jsonrpc/1.0.0 \
         com/google/code/gson/gson/2.14.0 \
         com/google/guava/guava/33.2.1-jre \
         com/google/guava/failureaccess/1.0.2 \
         com/google/errorprone/error_prone_annotations/2.48.0 \
         org/checkerframework/checker-qual/3.42.0 \
         com/google/j2objc/j2objc-annotations/3.0.0 \
         io/get-coursier/interface/1.0.18; do
  [ -d "$B2/$c" ] || fail "PC-extra coord missing: $c"
  njar=$(find "$B2/$c" -maxdepth 1 -type f -name '*.jar' | wc -l)
  npom=$(find "$B2/$c" -maxdepth 1 -type f -name '*.pom' | wc -l)
  [ "$njar" -eq 1 ] || fail "PC-extra coord $c: expected exactly 1 jar, got $njar"
  [ "$npom" -eq 1 ] || fail "PC-extra coord $c: expected exactly 1 pom, got $npom"
done
ok "all 11 required PC-extra coordinates present with exactly 1 jar + 1 pom"
nside=$(find "$CROOT" -type f \( -name '.*' -o -name 'maven-metadata*' \) | wc -l)
[ "$nside" -eq 0 ] || fail "stale sidecar dotfiles / maven-metadata remain in cache ($nside)"
ok "no sidecar dotfiles / maven-metadata in cache"

echo "== E. marker-inert compile comparison =="
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cat > "$WORK/Fixture.scala" <<'SCALA'
package zaozi.shadowcheck
object Fixture:
  val answer: Int = 42
  def add(a: Int, b: Int): Int = a + b + answer
  final class Holder(val name: String):
    def greet: String = "hi " + name
SCALA
# Common compiler runtime classpath (everything except the compiler jar itself),
# taken from the shadow cache so both runs are identical but for the compiler.
cp_common=""
for j in scala3-library_3/$VER/scala3-library_3-$VER.jar \
         scala-library/$VER/scala-library-$VER.jar \
         tasty-core_3/$VER/tasty-core_3-$VER.jar \
         scala3-interfaces/$VER/scala3-interfaces-$VER.jar; do
  cp_common="$cp_common:$M/$j"
done
cp_common="$cp_common:$B2/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar"
cp_common="$cp_common:$B2/org/scala-sbt/compiler-interface/1.10.7/compiler-interface-1.10.7.jar"
cp_common="$cp_common:$B2/org/scala-sbt/util-interface/1.10.7/util-interface-1.10.7.jar"
compile() { # $1 compiler jar, $2 outdir, $3 stdout file, $4 stderr file, $5 extra java opts
  mkdir -p "$2"
  # JVM -cp runs the compiler; dotc -classpath gives the SOURCE its scala libraries
  # (without it dotc reports "Could not find package scala").
  java ${5:-} -cp "$1$cp_common" dotty.tools.dotc.Main -classpath "$cp_common" -d "$2" \
    "$WORK/Fixture.scala" >"$3" 2>"$4"
}
compile "$STOCK_C" "$WORK/out-stock"   "$WORK/stock.out"   "$WORK/stock.err"   ""
compile "$CJAR"    "$WORK/out-patched" "$WORK/patched.out" "$WORK/patched.err" ""
# Fail closed: ALL compile products (incl. .tasty) must be byte-identical — no fallback.
diff -r "$WORK/out-stock" "$WORK/out-patched" >/dev/null \
  || fail "patched (marker unset) compile products differ from stock (incl. .class/.tasty)"
ok "patched (marker unset) compile products byte-identical to stock (.class + .tasty)"
# And the unset patched run must produce the same stdout + diagnostics as stock.
diff "$WORK/stock.out" "$WORK/patched.out" >/dev/null || fail "patched (unset) stdout differs from stock"
diff "$WORK/stock.err" "$WORK/patched.err" >/dev/null || fail "patched (unset) diagnostics differ from stock"
ok "patched (marker unset) stdout + diagnostics identical to stock"
if grep -q "zaozi-shadow-marker" "$WORK/patched.err"; then fail "marker emitted without the property"; fi
ok "patched compiler emits no marker when zaozi.shadow.marker is unset"
compile "$CJAR" "$WORK/out-gated" "$WORK/gated.out" "$WORK/gated.err" "-Dzaozi.shadow.marker=true"
grep -qF "zaozi-shadow-marker compiler org.scala-lang:scala3-compiler_3:$VER" "$WORK/gated.err" \
  || fail "gated marker not emitted with the property set"
ok "patched compiler emits the gated marker with -Dzaozi.shadow.marker=true"

echo ""
echo "ALL $PASS CHECKS PASSED"
