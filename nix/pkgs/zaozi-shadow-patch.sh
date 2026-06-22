#!/usr/bin/env bash
# Apply the Zaozi shadow marker patch to a scala3 source tree, in place, from the
# tree root, before `sbt package`. Minimal, removable, and PROPERTY-GATED: it has no
# effect unless the JVM is started with -Dzaozi.shadow.marker=true. The markers prove
# the patched compiler/PC bytes are the ones actually loaded; the real navigation
# patches replace them later.
#
#   bash zaozi-shadow-patch.sh [version] [scala3SourceRev]
set -euo pipefail
VER="${1:-3.8.4}"
REV="${2:-unknown}"

# (1) Provenance marker resources packaged into each jar (commonSettings maps
#     Compile/resourceDirectory -> <project>/resources).
mk_marker() { # $1 = project resources dir, $2 = artifact id
  mkdir -p "$1/META-INF/zaozi-shadow"
  cat > "$1/META-INF/zaozi-shadow/org.scala-lang-$2-$VER.properties" <<EOF
artifact=org.scala-lang:$2:$VER
zaoziShadow=true
scala3SourceRev=$REV
patchSet=marker-v1
builtBy=nix
EOF
}
mk_marker compiler/resources scala3-compiler_3
mk_marker presentation-compiler/resources scala3-presentation-compiler_3

# (2) Compiler behavioral marker: one stderr line at the core compile entry, gated.
perl -0pi -e 's/(\n  def process\(args: Array\[String\], rootCtx: Context\): Reporter = \{\n)/$1    if (sys.props.get("zaozi.shadow.marker").contains("true"))\n      System.err.println("zaozi-shadow-marker compiler org.scala-lang:scala3-compiler_3:'"$VER"'")\n/' \
  compiler/src/dotty/tools/dotc/Driver.scala

# (3) PC behavioral marker: inject a __zaozi_marker__ completion when gated.
PC=presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala
perl -0pi -e 's/\n      new CompletionProvider\(/\n      val __zaoziCompletionList = new CompletionProvider(/' "$PC"
perl -0pi -e 's/(\n      \)\.completions\(\)\n)(    \}\(params\.toQueryContext\))/$1      if sys.props.get("zaozi.shadow.marker").contains("true") then\n        val __zaoziItems = __zaoziCompletionList.getItems\n        if __zaoziItems != null then __zaoziItems.add(0, new l.CompletionItem("__zaozi_marker__"))\n      __zaoziCompletionList\n$2/' "$PC"

# Fail closed if any of the three edits did not take.
grep -q "zaozi-shadow-marker compiler" compiler/src/dotty/tools/dotc/Driver.scala
grep -q "__zaozi_marker__" "$PC"
test -f compiler/resources/META-INF/zaozi-shadow/org.scala-lang-scala3-compiler_3-$VER.properties
test -f presentation-compiler/resources/META-INF/zaozi-shadow/org.scala-lang-scala3-presentation-compiler_3-$VER.properties
echo "zaozi-shadow sentinel patch applied (VER=$VER REV=$REV)"
