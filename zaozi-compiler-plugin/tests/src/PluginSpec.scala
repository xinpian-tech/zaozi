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

      // (rewrittenAccessTexts, selectDynamicCallCount) over every retained Inlined.call.
      def inlinedCalls(withPlugin: Boolean): (List[String], Int) =
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
        val traverser      = new tpd.TreeTraverser:
          override def traverse(
            tree: tpd.Tree
          )(
            using DottyContext
          ): Unit = tree match
            case inlined: tpd.Inlined =>
              val callSym = inlined.call.symbol
              if callSym.exists && callSym.name.toString == "field1" && callSym.owner.name.toString == "NavBundle"
              then
                val span = inlined.call.span
                fieldAccesses += buffer.substring(span.start, span.end)
              if callSym.exists && callSym.name.toString == "selectDynamic" then dynCalls += 1
              traverse(inlined.call)
              inlined.bindings.foreach(traverse)
              traverse(inlined.expansion)
            case _ => traverseChildren(tree)
        traverser.traverse(driver.compilationUnits(uri).tpdTree)
        (fieldAccesses.result(), dynCalls)

      val (rewritten, dynLeft) = inlinedCalls(withPlugin = true)
      // The retained call of the access is now a ref to NavBundle#field1, spanning the access.
      assert(rewritten == List("io.field1"))
      assert(dynLeft == 0)

      val (baseline, dynBaseline) = inlinedCalls(withPlugin = false)
      // Without the plugin the retained call still points at Referable#selectDynamic.
      assert(baseline.isEmpty)
      assert(dynBaseline > 0)
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
