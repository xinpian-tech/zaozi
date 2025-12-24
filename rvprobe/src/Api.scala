// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

import me.jiuyang.rvprobe.constraints.*

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.*

import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, LocationApi, Operation, Type, Value, given}

import java.lang.foreign.Arena

def recipe(
  name:  String,
  sets:  Seq[Recipe ?=> SetConstraint]
)(
  using Arena,
  Context,
  Block
)(block: Recipe ?=> Unit
): Recipe = {
  given Recipe = new Recipe(name)

  // assert sets are valid
  val includedSets: List[Ref[Bool]] = sets.toList.map(f =>
    f(
      using summon[Recipe]
    ).toRef
  )
  // assert that sets are mutually exclusive
  val excludedSets = summon[Recipe].allSets.diff(includedSets)

  smtAssert(smtAnd(includedSets*))
  smtAssert(!smtOr(excludedSets*))

  block

  summon[Recipe]
}

// create an instruction with given index (Legacy/Mixed)
def instruction(
  idx:   Int
)(
  using Arena,
  Context,
  Block,
  Recipe
)(block: Index ?=> Constraint
): Unit = {
  val index = new Index(idx)
  summon[Recipe].addIndex(index)

  summon[Recipe].addOpcode(
    index,
    (i: Index) =>
      block(
        using i
      ).toRef
  )
}

// create an instruction with explicit type predicate and parameter predicate
def instruction(
  idx:    Int,
  opcode: Index ?=> InstConstraint
)(
  using Arena,
  Context,
  Block,
  Recipe
)(params: Index ?=> ArgConstraint
): Unit = {
  val index = new Index(idx)
  summon[Recipe].addIndex(index)

  // register type and parameter predicates for staged emission
  summon[Recipe].addOpcode(
    index,
    (i: Index) =>
      opcode(
        using i
      ).toRef
  )
  summon[Recipe].addArg(
    index,
    (i: Index) =>
      params(
        using i
      ).toRef
  )
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
