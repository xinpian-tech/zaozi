// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.plugin

import dotty.tools.dotc.semanticdb.{Range, SymbolOccurrence, TextDocument, TextDocuments}
import utest.*

/** Asserts the real `.semanticdb` output of the plugin.
  *
  * Each test drives an in-process scalac (`dotty.tools.dotc.Main`) with `-Xsemanticdb` and
  * `-Xplugin:<the built plugin jar>` over the fixture sources, then parses the emitted protobuf documents and checks
  * the injected records.
  */
object PluginSpec extends TestSuite:
  private val pluginJar   = sys.env("ZAOZI_PLUGIN_JAR")
  private val fixtureCp   = sys.env("ZAOZI_FIXTURE_CLASSPATH")
  private val fixtureSrc  = os.Path(sys.env("ZAOZI_FIXTURE_SOURCES"))
  private val scratchRoot = os.temp.dir(prefix = "zaozi-plugin-spec")

  private val bundlesScala = "PluginFixtureBundles.scala"
  private val usesScala    = "PluginFixtureUses.scala"
  private val plainScala   = "PluginFixturePlain.scala"

  private val selectDynamicSymbol = "me/jiuyang/zaozi/reftpe/Referable#selectDynamic()."

  private def compile(
    out:        os.Path,
    sources:    Seq[String],
    withPlugin: Boolean = true,
    extraCp:    Seq[os.Path] = Nil,
    extraFlags: Seq[String] = Nil
  ): Unit =
    os.makeDir.all(out)
    val classpath = (fixtureCp +: extraCp.map(_.toString)).mkString(java.io.File.pathSeparator)
    // -experimental: zaozi itself is compiled with -experimental, which marks its definitions
    // @experimental for downstream code; fixtures consume zaozi like any user code does. The
    // plugin itself neither needs nor uses experimental compiler API.
    val args      =
      Seq(
        "-classpath",
        classpath,
        "-d",
        out.toString,
        "-experimental",
        "-Xsemanticdb",
        "-sourceroot",
        fixtureSrc.toString
      )
        ++ (if withPlugin then Seq(s"-Xplugin:$pluginJar") else Nil)
        ++ extraFlags
        ++ sources.map(name => (fixtureSrc / name).toString)
    val reporter  = dotty.tools.dotc.Main.process(args.toArray)
    assert(!reporter.hasErrors)

  private def semanticdbFile(base: os.Path, source: String): os.Path =
    base / "META-INF" / "semanticdb" / s"$source.semanticdb"

  private def readDoc(base: os.Path, source: String): TextDocument =
    val docs = TextDocuments.parseFrom(os.read.bytes(semanticdbFile(base, source)))
    assert(docs.documents.length == 1)
    docs.documents.head

  /** Slice the fixture source text at a SemanticDB range (0-based lines/columns). */
  private def textAt(source: String, range: Range): String =
    val lines = os.read(fixtureSrc / source).split("\n", -1)
    assert(range.startLine == range.endLine)
    lines(range.startLine).substring(range.startCharacter, range.endCharacter)

  private def occurrencesOf(doc: TextDocument, symbol: String): Seq[SymbolOccurrence] =
    doc.occurrences.filter(_.symbol == symbol)

  /** Everything compiled once with the plugin; reused by the read-only assertions. */
  private lazy val mainOut: os.Path =
    val out = scratchRoot / "main"
    compile(out, Seq(bundlesScala, usesScala, plainScala))
    out

  val tests = Tests {
    test("definition SymbolInformation and DEFINITION occurrences per Aligned/Flipped field") {
      val doc            = readDoc(mainOut, bundlesScala)
      val expectedFields = Seq(
        "fixtures/TestBundle#input."  -> "input",
        "fixtures/TestBundle#output." -> "output",
        "fixtures/TestBundle#flag."   -> "flag",
        "fixtures/OuterBundle#inner." -> "inner",
        "fixtures/OuterBundle#data."  -> "data",
        "fixtures/OuterBundle#maybe." -> "maybe",
        "fixtures/FixtureIO#in."      -> "in",
        "fixtures/FixtureIO#out."     -> "out",
        "fixtures/FixtureIO#bundle."  -> "bundle",
        "fixtures/FixtureIO#nested."  -> "nested"
      )
      expectedFields.foreach { case (symbol, name) =>
        // (a) SymbolInformation with the exact expected symbol string
        assert(doc.symbols.exists(_.symbol == symbol))
        // (b) exactly one DEFINITION occurrence, located precisely on the `val` name
        val defs = occurrencesOf(doc, symbol).filter(_.role == SymbolOccurrence.Role.DEFINITION)
        assert(defs.length == 1)
        assert(textAt(bundlesScala, defs.head.range.get) == name)
      }
    }

    test("REFERENCE occurrences at every dynamic access site") {
      val doc      = readDoc(mainOut, usesScala)
      val expected = Seq(
        // (symbol, fieldNameInSource, occurrenceCount)
        ("fixtures/OuterBundle#inner.", "inner", 2), // io.inner and io.inner.flag
        ("fixtures/OuterBundle#data.", "data", 2),   // io.data and io.nested.data
        ("fixtures/OuterBundle#maybe.", "maybe", 1), // Option[BundleField[_]] member
        ("fixtures/TestBundle#flag.", "flag", 1),
        ("fixtures/TestBundle#input.", "input", 1),  // io.bundle.input, cross-bundle chain
        ("fixtures/FixtureIO#bundle.", "bundle", 1),
        ("fixtures/FixtureIO#nested.", "nested", 1),
        ("fixtures/FixtureIO#out.", "out", 1)
      )
      expected.foreach { case (symbol, name, count) =>
        val refs = occurrencesOf(doc, symbol)
        assert(refs.length == count)
        refs.foreach { occ =>
          assert(occ.role == SymbolOccurrence.Role.REFERENCE)
          // (c) the range is exactly the field-name span of the `io.field` access
          assert(textAt(usesScala, occ.range.get) == name)
        }
      }
      // every selectDynamic occurrence was accounted for and rewritten
      assert(occurrencesOf(doc, selectDynamicSymbol).isEmpty)
    }

    test("idempotence: recompilation produces identical records without duplicates") {
      val doc1 = readDoc(mainOut, usesScala)
      compile(mainOut, Seq(bundlesScala, usesScala, plainScala))
      val doc2 = readDoc(mainOut, usesScala)
      assert(doc1 == doc2)
      // (d) no duplicate occurrences or symbol infos anywhere
      Seq(bundlesScala, usesScala).foreach { source =>
        val doc = readDoc(mainOut, source)
        assert(doc.occurrences.distinct.length == doc.occurrences.length)
        assert(doc.symbols.map(_.symbol).distinct.length == doc.symbols.length)
      }
    }

    test("a file with no bundles is byte-identical to a plugin-less compile") {
      val withPlugin    = scratchRoot / "plain-on"
      val withoutPlugin = scratchRoot / "plain-off"
      compile(withPlugin, Seq(plainScala), withPlugin = true)
      compile(withoutPlugin, Seq(plainScala), withPlugin = false)
      val on            = os.read.bytes(semanticdbFile(withPlugin, plainScala))
      val off           = os.read.bytes(semanticdbFile(withoutPlugin, plainScala))
      assert(java.util.Arrays.equals(on, off))
    }

    test("incremental: a usage file is enhanced without the defining file in the run") {
      val defsOut = scratchRoot / "inc-defs"
      val usesOut = scratchRoot / "inc-uses"
      compile(defsOut, Seq(bundlesScala))
      // Second, separate run: only the usage file, bundles come from the classpath.
      compile(usesOut, Seq(usesScala), extraCp = Seq(defsOut))
      val doc     = readDoc(usesOut, usesScala)
      assert(occurrencesOf(doc, "fixtures/OuterBundle#inner.").length == 2)
      assert(occurrencesOf(doc, "fixtures/TestBundle#input.").length == 1)
      assert(occurrencesOf(doc, selectDynamicSymbol).isEmpty)
    }

    test("-semanticdb-target is honored") {
      val out    = scratchRoot / "target-classes"
      val target = scratchRoot / "target-semanticdb"
      compile(out, Seq(bundlesScala, usesScala), extraFlags = Seq("-semanticdb-target", target.toString))
      assert(!os.exists(semanticdbFile(out, usesScala)))
      val doc    = readDoc(target, usesScala)
      assert(occurrencesOf(doc, "fixtures/OuterBundle#inner.").nonEmpty)
      assert(occurrencesOf(doc, selectDynamicSymbol).isEmpty)
    }

    test("interactive: the nav phase resolves a dynamic access to the bundle field") {
      // The PC island (and Metals) loads the plugin through the module's -Xplugin scalacOption;
      // in the interactive pipeline the dispatched ZaoziPcNavPhase must rewrite the retained
      // `Inlined.call` of `io.field1` into a typed ref of the field val, which is what makes
      // symbol-at-cursor (definition/hover) land on `val field1 = Aligned(...)`.
      val buffer =
        """package navprobe
          |
          |import me.jiuyang.zaozi.*
          |import me.jiuyang.zaozi.default.{*, given}
          |import me.jiuyang.zaozi.reftpe.Referable
          |import me.jiuyang.zaozi.valuetpe.*
          |import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
          |
          |import java.lang.foreign.Arena
          |
          |class NavBundle extends Bundle:
          |  val field1 = Aligned(UInt(8))
          |
          |object NavUse:
          |  def use(
          |    io: Referable[NavBundle]
          |  )(
          |    using Arena,
          |    Block,
          |    Context,
          |    TypeImpl,
          |    InstanceContext
          |  ): Unit =
          |    val x = io.field1
          |    ()
          |""".stripMargin

      // (access texts that resolve to NavBundle#field1, selectDynamic calls left) over the whole tree.
      def fieldRefs(withPlugin: Boolean): (List[String], Int) =
        import dotty.tools.dotc.ast.tpd
        import dotty.tools.dotc.core.Contexts.Context as DottyContext
        val out            = scratchRoot / (if withPlugin then "nav-on" else "nav-off")
        os.makeDir.all(out)
        val options        = List("-classpath", fixtureCp, "-d", out.toString, "-experimental")
          ++ (if withPlugin then List(s"-Xplugin:$pluginJar") else Nil)
        val driver         = new dotty.tools.dotc.interactive.InteractiveDriver(options)
        val uri            = java.net.URI.create(s"file:///NavProbe${if withPlugin then "On" else "Off"}.scala")
        val diags          = driver.run(uri, dotty.tools.dotc.util.SourceFile.virtual(uri.toString, buffer))
        assert(diags.isEmpty)
        given DottyContext = driver.currentCtx
        val fieldAccesses  = List.newBuilder[String]
        var dynCalls       = 0
        def record(
          t: tpd.Tree
        )(
          using DottyContext
        ): Unit =
          val sym = t.symbol
          if sym.exists && sym.isTerm && sym.owner.isClass && sym.owner.name.toString == "NavBundle"
            && (t.isInstanceOf[tpd.Select] || t.isInstanceOf[tpd.Ident])
          then fieldAccesses += buffer.substring(t.span.start, t.span.end)
          if sym.exists && sym.name.toString == "selectDynamic" then dynCalls += 1
        val traverser      = new tpd.TreeTraverser:
          override def traverse(
            tree: tpd.Tree
          )(
            using DottyContext
          ): Unit =
            record(tree)
            tree match
              case inlined: tpd.Inlined =>
                traverse(inlined.call)
                inlined.bindings.foreach(traverse)
                traverse(inlined.expansion)
              case _ => traverseChildren(tree)
        traverser.traverse(driver.compilationUnits(uri).tpdTree)
        (fieldAccesses.result(), dynCalls)

      val (rewritten, dynLeft) = fieldRefs(withPlugin = true)
      // The access Inlined is replaced by a select on NavBundle#field1, spanning the access.
      assert(rewritten == List("io.field1"))
      assert(dynLeft == 0)

      val (baseline, dynBaseline) = fieldRefs(withPlugin = false)
      // Without the plugin the access stays a selectDynamic call and no field symbol is in the tree.
      assert(baseline.isEmpty)
      assert(dynBaseline > 0)
    }

    test("interactive: a dynamic access nested in another dynamic access stays navigable") {
      // `io.field1.field2` is two dynamic accesses in a row: the receiver of the outer one is the
      // (inlined) inner access, so the inner access sits inside a retained call. Both must resolve
      // to their field val, otherwise go-to inside the chain (the common `io.a.b := …` shape) lands
      // on the access itself.
      val buffer =
        """package navnested
          |
          |import me.jiuyang.zaozi.*
          |import me.jiuyang.zaozi.default.{*, given}
          |import me.jiuyang.zaozi.reftpe.Referable
          |import me.jiuyang.zaozi.valuetpe.*
          |import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
          |
          |import java.lang.foreign.Arena
          |
          |class NavNestedInner extends Bundle:
          |  val field2 = Aligned(UInt(8))
          |
          |class NavNestedOuter extends Bundle:
          |  val field1 = Aligned(new NavNestedInner)
          |
          |object NavNestedUse:
          |  def use(
          |    io: Referable[NavNestedOuter]
          |  )(
          |    using Arena,
          |    Block,
          |    Context,
          |    TypeImpl,
          |    InstanceContext
          |  ): Unit =
          |    val x = io.field1.field2
          |    ()
          |""".stripMargin

      // (texts of the accesses that resolve to a navnested field, selectDynamic calls left) over the
      // whole tree. The option list mirrors what the BSP reports for a real target (a semanticdb
      // pass, a sourceroot and a doubled -Xplugin), so a pass here also covers the option shape the
      // PC island runs with.
      def nestedProbe(withPlugin: Boolean): (List[String], Int) =
        import dotty.tools.dotc.ast.tpd
        import dotty.tools.dotc.core.Contexts.Context as DottyContext
        import dotty.tools.dotc.core.Flags
        val out            = scratchRoot / (if withPlugin then "navnested-on" else "navnested-off")
        os.makeDir.all(out)
        val options        = List(
          "-classpath",
          fixtureCp,
          "-d",
          out.toString,
          "-experimental",
          "-Xsemanticdb",
          "-sourceroot",
          fixtureSrc.toString
        )
          ++ (if withPlugin then List(s"-Xplugin:$pluginJar", s"-Xplugin:$pluginJar") else Nil)
        val driver         = new dotty.tools.dotc.interactive.InteractiveDriver(options)
        val uri            = java.net.URI.create(s"file:///NavNestedProbe${if withPlugin then "On" else "Off"}.scala")
        val diags          = driver.run(uri, dotty.tools.dotc.util.SourceFile.virtual(uri.toString, buffer))
        assert(diags.isEmpty)
        given DottyContext = driver.currentCtx
        val fieldAccesses  = List.newBuilder[String]
        var dynCalls       = 0
        def record(
          t: tpd.Tree
        )(
          using DottyContext
        ): Unit =
          val sym = t.symbol
          if sym.exists && sym.isTerm && !sym.is(Flags.Method)
            && (sym.owner.name.toString == "NavNestedOuter" || sym.owner.name.toString == "NavNestedInner")
            && (t.isInstanceOf[tpd.Select] || t.isInstanceOf[tpd.Ident])
          then fieldAccesses += buffer.substring(t.span.start, t.span.end)
          if sym.exists && sym.name.toString == "selectDynamic" then dynCalls += 1
        val traverser      = new tpd.TreeTraverser:
          override def traverse(
            tree: tpd.Tree
          )(
            using DottyContext
          ): Unit =
            record(tree)
            tree match
              case inlined: tpd.Inlined =>
                traverse(inlined.call)
                inlined.bindings.foreach(traverse)
                traverse(inlined.expansion)
              case _ => traverseChildren(tree)
        traverser.traverse(driver.compilationUnits(uri).tpdTree)
        (fieldAccesses.result(), dynCalls)

      val (rewritten, dynLeft) = nestedProbe(withPlugin = true)
      // Both accesses must resolve to their field val, and no selectDynamic may remain in the chain.
      // The traversal meets the outer select first (the inner access is its receiver), hence the
      // order; comparing tuples makes utest print both observed values when this fails.
      assert((rewritten, dynLeft) == (List("io.field1.field2", "io.field1"), 0))

      val (baseline, dynBaseline) = nestedProbe(withPlugin = false)
      assert(baseline.isEmpty)
      assert(dynBaseline > 0)
    }

    test("interactive: symbol-at-cursor resolves both levels of a dynamic access") {
      // The plugin rewrites every dynamic access into a ref to the field val (tested above). This
      // test additionally mimics what a presentation-compiler client does for hover/go-to: take the
      // innermost typed node whose span contains the cursor and read its symbol. If the rewrite is
      // only visible in a `Inlined.call` that navigation does not prefer, the client sees the
      // synthetic val of the expansion instead of the field.
      val buffer =
        """package navprobe2
          |
          |import me.jiuyang.zaozi.*
          |import me.jiuyang.zaozi.default.{*, given}
          |import me.jiuyang.zaozi.reftpe.Referable
          |import me.jiuyang.zaozi.valuetpe.*
          |import org.llvm.mlir.scalalib.capi.ir.{Block, Context}
          |
          |import java.lang.foreign.Arena
          |
          |class NavProbeInner extends Bundle:
          |  val field2 = Aligned(UInt(8))
          |
          |class NavProbeOuter extends Bundle:
          |  val field1 = Aligned(new NavProbeInner)
          |
          |object NavProbeUse:
          |  def use(
          |    io: Referable[NavProbeOuter]
          |  )(
          |    using Arena,
          |    Block,
          |    Context,
          |    TypeImpl,
          |    InstanceContext
          |  ): Unit =
          |    val single = io.field1
          |    val nested = io.field1.field2
          |    ()
          |""".stripMargin

      def symbolAt(cursor: Int): List[String] =
        import dotty.tools.dotc.ast.tpd
        import dotty.tools.dotc.core.Contexts.Context as DottyContext
        val out            = scratchRoot / "navprobe2"
        os.makeDir.all(out)
        val options        = List(
          "-classpath",
          fixtureCp,
          "-d",
          out.toString,
          "-experimental",
          "-Xsemanticdb",
          "-sourceroot",
          fixtureSrc.toString,
          s"-Xplugin:$pluginJar"
        )
        val driver         = new dotty.tools.dotc.interactive.InteractiveDriver(options)
        val uri            = java.net.URI.create("file:///NavProbe2.scala")
        val diags          = driver.run(uri, dotty.tools.dotc.util.SourceFile.virtual(uri.toString, buffer))
        assert(diags.isEmpty)
        given DottyContext = driver.currentCtx
        // Every symbol-bearing node whose span contains the cursor, innermost first; this is the
        // candidate list a client's symbol-at-cursor walks.
        val found          = List.newBuilder[(Int, Int, String)]
        val traverser      = new tpd.TreeTraverser:
          override def traverse(
            t: tpd.Tree
          )(
            using DottyContext
          ): Unit =
            if t.span.exists && t.span.start <= cursor && cursor < t.span.end && t.symbol.exists then
              found += ((t.span.start, t.span.end, t.symbol.name.toString))
            traverseChildren(t)
        traverser.traverse(driver.compilationUnits(uri).tpdTree)
        found.result().sortBy(e => (-e._1, e._2)).take(6).map { (s, e, name) =>
          s"$name@${buffer.substring(s, e).trim}"
        }

      val singleField  = buffer.indexOf("val single = io.field1") + "val single = io.".length
      val nestedField1 = buffer.indexOf("val nested = io.field1.field2") + "val nested = io.".length
      val nestedField2 = nestedField1 + "field1.".length
      val observed     = List(symbolAt(singleField), symbolAt(nestedField1), symbolAt(nestedField2))
      // Regression guard for the whole-node rewrite: the innermost candidate at every level must be
      // the field select, not the expansion's synthetic `Referable_this` val (which won by spanning
      // the whole access) nor the enclosing val definition.
      val heads        = observed.map(_.headOption)
      assert(heads == List(Some("field1@io.field1"), Some("field1@io.field1"), Some("field2@io.field1.field2")))
    }

    test("batch: tasty and bytecode are byte-identical with and without the plugin") {
      // The nav phase must never even schedule in the batch pipeline (a batch rewrite of
      // Inlined.call would be pickled into published TASTy), and the SemanticDB enhancer must
      // never touch trees — so every .tasty/.class artifact must be byte-identical; only the
      // .semanticdb payloads may differ.
      val on                       = scratchRoot / "inert-on"
      val off                      = scratchRoot / "inert-off"
      compile(on, Seq(bundlesScala, usesScala), withPlugin = true)
      compile(off, Seq(bundlesScala, usesScala), withPlugin = false)
      def artifacts(base: os.Path) =
        os.walk(base).filter(p => os.isFile(p) && (p.ext == "tasty" || p.ext == "class")).map(_.relativeTo(base))
      val rels                     = artifacts(off)
      assert(rels.nonEmpty)
      assert(artifacts(on).toSet == rels.toSet)
      rels.foreach { rel =>
        val relPath   = rel.toString
        val identical = java.util.Arrays.equals(os.read.bytes(on / rel), os.read.bytes(off / rel))
        assert(identical, relPath.nonEmpty) // relPath in scope so a failure names the artifact
      }
    }

    test("interactive presentation-compiler pipeline stays functional with the plugin loaded") {
      // The PC island (and Metals) reuses the batch scalacOptions, so InteractiveDriver loads the
      // plugin too. Its pipeline (parser/typer/SetRootTree/cookComments) has none of the phase's
      // anchors; the plugin must contribute no phases there instead of crashing Plugins.schedule
      // (NoSuchElementException: key not found: extractSemanticDBExtractSemanticInfo) during
      // driver construction.
      val out    = scratchRoot / "interactive-out"
      os.makeDir.all(out)
      val driver = new dotty.tools.dotc.interactive.InteractiveDriver(
        List("-classpath", fixtureCp, "-d", out.toString, "-experimental", s"-Xplugin:$pluginJar")
      )
      val uri    = java.net.URI.create("file:///InteractiveProbe.scala")
      val code   = "class InteractiveProbe { val x: Int = 1; def f: Int = x }"
      val diags  = driver.run(uri, dotty.tools.dotc.util.SourceFile.virtual(uri.toString, code))
      assert(diags.isEmpty)
      val unit   = driver.compilationUnits.get(uri)
      assert(unit.isDefined)
      assert(!unit.get.tpdTree.isEmpty)
    }
  }
