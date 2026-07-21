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
  }
