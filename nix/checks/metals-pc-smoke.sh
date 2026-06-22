#!/usr/bin/env bash
# Metals presentation-compiler resolve+load smoke (task15 of the Metals integration).
#
# Proves that the per-project Scala 3.8.x presentation compiler that the pinned
# Metals (nixpkgs 1.6.5) resolves can actually be LOADED under JDK 25 — i.e. the
# class Metals instantiates, `dotty.tools.pc.ScalaPresentationCompiler`, links from
# the version-matched `scala3-presentation-compiler_3:<version>` jar. The full
# editor-grade "Metals server returns a real completion on a BSP workspace" smoke is
# the task11 headless-LSP harness; this is the task15 gate.
#
# Mechanism: download the PC jar + its runtime dep closure with `curl` (which honors
# https_proxy in proxied/sandboxed networks, unlike scala-cli's native resolver),
# combine with the already-resolved Scala compiler classpath from Mill, and load the
# PC class with `jshell` (local, no network). Run from inside `nix develop`.
#   bash nix/checks/metals-pc-smoke.sh [scalaVersion]   # default 3.8.4
set -uo pipefail

PC_VERSION="${1:-3.8.4}"
MTAGS_IF="1.6.7"        # org.scalameta:mtags-interfaces (scala.meta.pc.PresentationCompiler)
GUAVA="33.2.1-jre"; FAILUREACCESS="1.0.2"; LZ4="1.8.0"; CS_IFACE="1.0.18"
PXY="${https_proxy:-${HTTPS_PROXY:-}}"
CURL_PXY=(); [ -n "$PXY" ] && CURL_PXY=(-x "$PXY")
base="https://repo1.maven.org/maven2"
LIB="$(mktemp -d)"; trap 'rm -rf "$LIB"' EXIT

# PC runtime dependency closure (from the PC pom; scala3-compiler/library come from
# Mill's scalaCompilerClasspath below). lsp4j is intentionally omitted — it is a
# Metals-side dep needed only to *construct* the PC, not to load/link the class.
urls=(
  "org/scala-lang/scala3-presentation-compiler_3/$PC_VERSION/scala3-presentation-compiler_3-$PC_VERSION.jar"
  "org/scalameta/mtags-interfaces/$MTAGS_IF/mtags-interfaces-$MTAGS_IF.jar"
  "com/google/guava/guava/$GUAVA/guava-$GUAVA.jar"
  "com/google/guava/failureaccess/$FAILUREACCESS/failureaccess-$FAILUREACCESS.jar"
  "org/lz4/lz4-java/$LZ4/lz4-java-$LZ4.jar"
  "io/get-coursier/interface/$CS_IFACE/interface-$CS_IFACE.jar"
)
echo "scala3-presentation-compiler version: $PC_VERSION ; proxy: ${PXY:-<none>}"
for u in "${urls[@]}"; do
  curl -fsS "${CURL_PXY[@]}" "$base/$u" -o "$LIB/$(basename "$u")" \
    && echo "ok  $(basename "$u")" || { echo "FAIL download $u"; exit 1; }
done

SCALACP="$(mill show zaozi.scalaCompilerClasspath 2>/dev/null \
  | jq -r '.[]' | sed -E 's#^qref:v[0-9]+:[0-9a-f]+:##' | tr '\n' ':')"
CP="$(ls "$LIB"/*.jar | tr '\n' ':')$SCALACP"

cat > "$LIB/probe.jsh" <<'JSH'
var cls = Class.forName("dotty.tools.pc.ScalaPresentationCompiler", false, ClassLoader.getSystemClassLoader());
System.out.println("PC_LOADED " + cls.getName());
System.out.println("PC_SOURCE " + cls.getProtectionDomain().getCodeSource().getLocation());
try { var c = cls.getDeclaredConstructor(); c.setAccessible(true); var o = c.newInstance(); System.out.println("PC_INSTANTIATED " + o.getClass().getName()); }
catch (Throwable t) { System.out.println("PC_INSTANTIATE_SKIPPED " + t); }
/exit
JSH

echo "=== jshell load (JDK 25) ==="
OUT="$(jshell --class-path "$CP" "$LIB/probe.jsh" 2>/dev/null)"
echo "$OUT"
# PASS = PC class loaded from the version-matched jar.
if echo "$OUT" | grep -q "PC_LOADED dotty.tools.pc.ScalaPresentationCompiler" \
   && echo "$OUT" | grep -q "scala3-presentation-compiler_3-$PC_VERSION.jar"; then
  echo "RESULT: PASS (PC $PC_VERSION loads under $(java -version 2>&1 | grep -iv 'Picked up' | head -1))"
  exit 0
else
  echo "RESULT: FAIL"; exit 1
fi
