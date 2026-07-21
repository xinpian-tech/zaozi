// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package dotty.tools.dotc.semanticdb

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols.Symbol
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span

/** Package-private access into `dotty.tools.dotc.semanticdb` for the zaozi SemanticDB enhancer plugin.
  *
  * The compiler's own symbol-naming ([[SemanticSymbolBuilder]]), range conversion and [[SymbolInformation]]
  * construction helpers are `private[semanticdb]`. The plugin must emit symbol strings that are byte-identical to what
  * `ExtractSemanticDB` itself would produce for a real `val` member (otherwise occurrences would not group with the
  * definitions already present in the document), so instead of re-implementing the SemanticDB symbol grammar by string
  * concatenation we open a small facade inside the package and delegate to the compiler's own implementation.
  *
  * Instances are cheap and must be scoped to a single compilation unit: [[SemanticSymbolBuilder]] numbers local symbols
  * (`local0`, `local1`, ...) per document. The facade only guarantees stable names for global symbols; callers must
  * check [[isGlobal]] before using [[symbolName]].
  */
final class ZaoziSemanticdbFacade(
  using Context):
  private given builder: SemanticSymbolBuilder = SemanticSymbolBuilder()
  private given typeOps: TypeOps               = TypeOps()
  private given LinkMode = LinkMode.SymlinkChildren

  import Scala3.given

  /** The SemanticDB symbol string the compiler itself would emit for `sym`. */
  def symbolName(sym: Symbol): String = sym.symbolName

  /** Whether `sym` gets a stable (non-`localN`) SemanticDB symbol. */
  def isGlobal(sym: Symbol): Boolean = sym.isGlobal

  /** The SemanticDB range for a span, converted exactly as `ExtractSemanticDB` does. */
  def range(span: Span, source: SourceFile): Option[Range] = Scala3.range(span, source)

  /** The `SymbolInformation` the compiler itself would emit for a `val` member. */
  def valSymbolInformation(sym: Symbol): SymbolInformation =
    sym.symbolInfo(Set(Scala3.SymbolKind.Val))
