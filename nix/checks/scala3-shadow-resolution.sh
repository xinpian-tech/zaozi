#!/usr/bin/env bash
# AC-4 (compiler-consumer half): prove that, against an ISOLATED writable copy of the
# shadow cache (with HOME / XDG_CACHE_HOME / COURSIER_CACHE / IVY_HOME / JAVA_TOOL_OPTIONS
# sanitized and no network), the compiler JVM loads the PATCHED scala3-compiler_3:3.8.4
# from the isolated cache — provenance asserted from the actual JVM via the loaded class's
# CodeSource, not merely from a resolver. Plus the cache-state matrix.
#
#   scala3-shadow-resolution.sh --shadow-cache DIR --shadow-jars DIR --stock-cache DIR
#
# Needs on PATH: java, javac, jq, sha256sum, grep, find.
set -euo pipefail

VER=3.8.4
SC=; SJ=; STOCK_SC=
while [ $# -gt 0 ]; do
  case "$1" in
    --shadow-cache) SC="$2"; shift 2 ;;
    --shadow-jars)  SJ="$2"; shift 2 ;;
    --stock-cache)  STOCK_SC="$2"; shift 2 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
for v in SC SJ STOCK_SC; do [ -n "${!v}" ] || { echo "missing arg $v" >&2; exit 2; }; done

PASS=0
ok()   { PASS=$((PASS+1)); echo "PASS  $1"; }
fail() { echo "FAIL  $1" >&2; exit 1; }
sha()  { sha256sum "$1" | cut -d' ' -f1; }

# Isolated, sanitized environment: the copied cache is the sole authoritative source;
# the user's ~/.cache/coursier, ~/.ivy2 and any proxy are never consulted.
ROOT=$(mktemp -d)
trap 'rm -rf "$ROOT"' EXIT
export HOME="$ROOT/home" XDG_CACHE_HOME="$ROOT/xdg" COURSIER_CACHE="$ROOT/cc" IVY_HOME="$ROOT/ivy"
mkdir -p "$HOME" "$XDG_CACHE_HOME" "$COURSIER_CACHE" "$IVY_HOME"
unset JAVA_TOOL_OPTIONS JAVA_OPTS SBT_OPTS http_proxy https_proxy HTTP_PROXY HTTPS_PROXY 2>/dev/null || true

# Writable isolated copies of each cache state.
PATCHED="$ROOT/patched"; cp -rL "$SC/cache" "$PATCHED"; chmod -R u+w "$PATCHED"
STOCK="$ROOT/stock";     cp -rL "$STOCK_SC/cache" "$STOCK"; chmod -R u+w "$STOCK"
EMPTY="$ROOT/empty";     mkdir -p "$EMPTY/https"

mvn() { printf '%s' "$1/https/repo1.maven.org/maven2"; }
# Resolve a coordinate by its Maven path inside a cache root (the cache IS the repo).
resolve_compiler() { # $1 cache root -> prints jar path, or returns 1 if absent
  local p; p="$(mvn "$1")/org/scala-lang/scala3-compiler_3/$VER/scala3-compiler_3-$VER.jar"
  [ -f "$p" ] && printf '%s' "$p"
}
# Compiler runtime classpath (the 7 deps) from a cache root.
cpof() {
  local b; b="$(mvn "$1")"
  printf '%s' \
"$b/org/scala-lang/scala3-library_3/$VER/scala3-library_3-$VER.jar:\
$b/org/scala-lang/scala-library/$VER/scala-library-$VER.jar:\
$b/org/scala-lang/tasty-core_3/$VER/tasty-core_3-$VER.jar:\
$b/org/scala-lang/scala3-interfaces/$VER/scala3-interfaces-$VER.jar:\
$b/org/scala-lang/modules/scala-asm/9.9.0-scala-1/scala-asm-9.9.0-scala-1.jar:\
$b/org/scala-sbt/compiler-interface/1.10.7/compiler-interface-1.10.7.jar:\
$b/org/scala-sbt/util-interface/1.10.7/util-interface-1.10.7.jar"
}

# Probe: from the actual JVM, where was dotty.tools.dotc.Driver loaded from?
mkdir -p "$ROOT/probe"
cat > "$ROOT/probe/Probe.java" <<'JAVA'
public class Probe {
  public static void main(String[] a) throws Exception {
    var loc = Class.forName("dotty.tools.dotc.Driver")
      .getProtectionDomain().getCodeSource().getLocation();
    System.out.println(loc.getPath());
  }
}
JAVA
javac -d "$ROOT/probe" "$ROOT/probe/Probe.java"
probe() { java -cp "$ROOT/probe:$1:$2" Probe; }   # $1 compiler jar, $2 common cp

cat > "$ROOT/F.scala" <<'SCALA'
package z
object F:
  val a: Int = 1
SCALA
compile() { # $1 compiler jar, $2 common cp, $3 outdir, $4 errfile, $5 extra java opts
  mkdir -p "$3"
  java ${5:-} -cp "$1:$2" dotty.tools.dotc.Main -classpath "$2" -d "$3" "$ROOT/F.scala" >/dev/null 2>"$4"
}

want=$(jq -r '.artifacts."scala3-compiler_3".jarSha256' "$SJ/share/zaozi-shadow/hashes.json")
[ -n "$want" ] && [ "$want" != null ] || fail "could not read published patched compiler hash"

echo "== 1. patched isolated cache: JVM-side provenance + gated marker =="
PJ=$(resolve_compiler "$PATCHED") || fail "patched cache: compiler not resolvable"
case "$PJ" in "$PATCHED"/*) ;; *) fail "resolved jar not under the isolated cache: $PJ";; esac
loc=$(probe "$PJ" "$(cpof "$PATCHED")")
case "$loc" in "$PATCHED"/*) ;; *) fail "JVM loaded Driver from outside the isolated cache: $loc";; esac
[ "$(sha "$loc")" = "$(sha "$PJ")" ] || fail "probed code source != resolved jar"
[ "$(sha "$PJ")" = "$want" ] || fail "loaded compiler hash != published patched ($(sha "$PJ") vs $want)"
ok "patched cache: JVM loads compiler from the isolated cache; path+hash == published patched"
compile "$PJ" "$(cpof "$PATCHED")" "$ROOT/o1" "$ROOT/e1" "-Dzaozi.shadow.marker=true"
grep -qF "zaozi-shadow-marker compiler org.scala-lang:scala3-compiler_3:$VER" "$ROOT/e1" \
  || fail "patched cache: gated compiler marker not emitted"
ok "patched cache: scalac emits the gated compiler marker"

echo "== 2. empty isolated cache (offline): resolution fails =="
if resolve_compiler "$EMPTY" >/dev/null; then fail "empty isolated cache unexpectedly resolved a compiler"; fi
ok "empty isolated cache: offline resolution fails (no patched bytes, no fallback)"

echo "== 3. stock isolated cache: stock bytes, no marker =="
KJ=$(resolve_compiler "$STOCK") || fail "stock cache: compiler not resolvable"
[ "$(sha "$KJ")" != "$want" ] || fail "stock cache compiler hash equals patched?!"
ok "stock isolated cache: resolves the stock compiler (hash != patched)"
compile "$KJ" "$(cpof "$STOCK")" "$ROOT/o3" "$ROOT/e3" "-Dzaozi.shadow.marker=true"
if grep -q "zaozi-shadow-marker" "$ROOT/e3"; then fail "stock compiler emitted the marker?!"; fi
ok "stock isolated cache: scalac emits no marker even with the property set"

echo ""
echo "ALL $PASS CHECKS PASSED"
