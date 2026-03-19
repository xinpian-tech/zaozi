// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.rvprobe.constraints.*

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}

import java.lang.foreign.Arena

// create an instruction with explicit type predicate and parameter predicate.
// T is the specific opaque instruction type (e.g. IsFence, IsAddi) returned by
// the corresponding isXxx() function.  The compiler automatically summons
// SpecFor[T]; if no explicit given exists, the low-priority noSpec instance
// is used and no additional constraint is injected.
def instruction[T <: InstConstraint](
  idx:         Int,
  opcode:      Index ?=> T
)(
  using arena: Arena,
  context:     Context,
  block:       Block,
  recipe:      Recipe
)(
  using sf:    SpecFor[T]
)(params:      Index ?=> ArgConstraint
): Unit = {
  val index = recipe.addIndex(
    new Index(idx)(
      using arena,
      context,
      block
    )
  )
  recipe.addStatement(Statement.Inst(idx))

  // Register constraints to Index itself
  index.addOpcodeConstraint((i: Index) =>
    opcode(
      using i
    ).toRef
  )
  index.addArgConstraint { (i: Index) =>
    val userConstraint = params(
      using i
    )
    // Merge spec-mandated constraints (if any) with the user-provided constraints.
    // Both are AND'd so both must be satisfied simultaneously.
    sf.spec(
      using arena,
      context,
      block,
      i
    ) match
      case Some(specConstraint) => (userConstraint & specConstraint).toRef
      case None                 => userConstraint.toRef
  }
}

// get an instruction with given index
def instruction(
  idx: Int
)(
  using Arena,
  Context,
  Block,
  Recipe
): Index = summon[Recipe].getIndex(idx)

def sequence(
  start: Int,
  end:   Int
)(
  using Arena,
  Context,
  Block,
  Recipe
): Seq[Index] =
  val recipe = summon[Recipe]
  (start until end).map(recipe.getIndex)

def distinct[D <: Data, R <: Referable[D]](
  indices: Iterable[Int]
)(field:   Index => R
)(
  using Recipe,
  Arena,
  Context,
  Block
): Unit =
  val recipe    = summon[Recipe]
  val indexObjs = indices.map(recipe.getIndex)
  smtAssert(smtDistinct(indexObjs.map(field).toSeq*))

def isRV64G(
): Seq[Recipe ?=> SetConstraint] =
  Seq(
    isRVI(),
    isRVM(),
    isRVA(),
    isRVF(),
    isRVD(),
    isRV64I(),
    isRV64M(),
    isRV64A(),
    isRV64F(),
    isRV64D()
  )

def isRV64GC(
): Seq[Recipe ?=> SetConstraint] =
  isRV64G() :+ isRVC()

// ================== Assembly Directive & Label DSL ==================

def label(
  name:         String
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Label(name))

def label(
  name:         String
)(body:         => Unit
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Label(name))
  body

def section(
  name:         String,
  flags:        String*
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Section(name, flags*))

def global(
  symbol:       String
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Global(symbol))

def align(
  n:            Int
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Align(n))

def word(
  value:        Long
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Word(value))

def dword(
  value:        Long
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Dword(value))

def zero(
  size:         Int
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Zero(size))

def balign(
  alignment:    Int
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Balign(alignment))

def pseudo(
  mnemonic:     String,
  operands:     String = ""
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Pseudo(mnemonic, operands))

def raw(
  content:      String
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Raw(content))

def labelRef(
  idx:          Int,
  targetLabel:  String
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.LabelRef(idx, targetLabel))

// ================== Pseudo-instruction helpers (raw emission) ==================

/** `li rd, imm` — load immediate (pseudo, expands to lui+addi or longer sequence). */
def li(
  rd:           Register,
  imm:          Long
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Raw(s"    li ${rd}, 0x${imm.toHexString}"))

/** `la rd, symbol` — load address of symbol (pseudo, expands to auipc+addi). */
def la(
  rd:           Register,
  symbol:       String
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Raw(s"    la ${rd}, $symbol"))
