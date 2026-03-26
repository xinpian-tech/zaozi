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
): Int = {
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
  idx
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

def space(
  size:         Int
)(
  using recipe: Recipe
): Unit =
  recipe.addStatement(Statement.Space(size))

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

// ================== Cross-index field constraints ==================

def crossFields(
  i: Int,
  f: Index => Ref[SInt],
  j: Int,
  g: Index => Ref[SInt]
)(
  using Arena,
  Context,
  Block,
  Recipe
): Unit =
  summon[Recipe].addCrossIndexConstraint { () =>
    smtAssert(f(summon[Recipe].getIndex(i)) === g(summon[Recipe].getIndex(j)))
  }

// ================== Fragment composition ==================

/** A composable test fragment: a block of DSL code + the fixed registers it uses.
  *
  * Fragments declare which hardcoded registers they write to. When composed, the solver ensures all `freshReg()`
  * variables avoid these fixed registers.
  *
  * {{{
  * val fpStress = Fragment(Set(x16, x17)) { // uses FP regs via fmvDX
  *   val tmp = freshReg()
  *   li(tmp, 0x774492720dbedb91L)
  *   fmvDX(x16, tmp)
  * }
  *
  * val btbPayload = Fragment(Set(x1, x11, x12)) {
  *   jal(x1, "target")
  *   lui(x11, 0x40000)
  *   ld(x12, x11, 0)
  * }
  *
  * // In constraints():
  * compose(fpStress, btbPayload)  // freshRegs auto-avoid x1, x11, x12, x16, x17
  * }}}
  */
class Fragment(
  val fixedRegs: Set[Register]
)(
  val emit: (Arena, Context, Block, Recipe) ?=> Unit
)

/** Compose fragments sequentially. Records all fixed registers, then emits all fragment code. */
def compose(
  fragments: Fragment*
)(
  using Arena,
  Context,
  Block,
  Recipe
): Unit =
  val recipe = summon[Recipe]
  fragments.foreach(_.fixedRegs.foreach(r => recipe.recordFixedReg(r.ordinal)))
  fragments.foreach(f => f.emit)

/** Declare fixed registers used by subsequent code. freshReg() will avoid these.
  *
  * Call before using hardcoded registers to prevent freshReg from being assigned the same physical register.
  */
def useFixed(
  regs: Register*
)(
  using Recipe
): Unit =
  regs.foreach(r => summon[Recipe].recordFixedReg(r.ordinal))

// ================== Symbolic register variables ==================

/** Create a fresh symbolic register variable constrained to x1–x31.
  *
  * Automatically pairwise distinct with all other freshRegs, and distinct from all fixed registers declared via
  * [[useFixed]] or [[Fragment]].
  */
def freshReg(
)(
  using Arena,
  Context,
  Block,
  Recipe
): Referable[SInt] =
  val recipe = summon[Recipe]
  val id     = recipe.nextFreshRegId()
  val v      = smtValue(s"freg_$id", SInt)
  smtAssert(v >= 1.S & v < 32.S)
  recipe.registerFreshReg(v)
  v

// ================== Pseudo-instruction helpers (raw emission) ==================

/** `li rd, imm` — load immediate (pseudo). Returns instruction idx. */
def li(
  rd:           Register,
  imm:          Long
)(
  using recipe: Recipe
): Int =
  recipe.recordFixedReg(rd.ordinal)
  val idx = recipe.nextIdx()
  recipe.addStatement(Statement.Pseudo("li", s"${rd}, 0x${imm.toHexString}"))
  idx

/** `li rd, imm` — symbolic rd. Placeholder `{rd}` resolved at render time. */
def li(
  rd:  Referable[SInt],
  imm: Long
)(
  using Arena,
  Context,
  Block,
  Recipe
): Int =
  val recipe = summon[Recipe]
  val idx    = recipe.nextIdx()
  val index  = recipe.addIndex(new Index(idx))
  index.addArgConstraint { (i: Index) =>
    given Index = i
    rdEqual(rd).toRef
  }
  recipe.addStatement(Statement.Pseudo("li", s"{rd}, 0x${imm.toHexString}", Some(idx)))
  idx

/** `la rd, symbol` — load address of symbol (pseudo). Returns instruction idx. */
def la(
  rd:           Register,
  symbol:       String
)(
  using recipe: Recipe
): Int =
  recipe.recordFixedReg(rd.ordinal)
  val idx = recipe.nextIdx()
  recipe.addStatement(Statement.Pseudo("la", s"${rd}, $symbol"))
  idx

/** `la rd, symbol` — symbolic rd. */
def la(
  rd:     Referable[SInt],
  symbol: String
)(
  using Arena,
  Context,
  Block,
  Recipe
): Int =
  val recipe = summon[Recipe]
  val idx    = recipe.nextIdx()
  val index  = recipe.addIndex(new Index(idx))
  index.addArgConstraint { (i: Index) =>
    given Index = i
    rdEqual(rd).toRef
  }
  recipe.addStatement(Statement.Pseudo("la", s"{rd}, $symbol", Some(idx)))
  idx
