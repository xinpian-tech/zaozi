// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena
import me.jiuyang.smtlib.SMTQuery
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, given_TypeApi, DialectApi as SMT}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import org.llvm.mlir.scalalib.dialect.smt.operation.{
  AndApi,
  AssertApi,
  BVAndApi,
  BVConstantApi,
  BVNotApi,
  BVOrApi,
  ConcatApi,
  DeclareFunApi,
  EqApi,
  ExtractApi,
  IteApi,
  NotApi,
  OrApi,
  given
}

private[prcm] class PRCMRelation(
  module:   Module,
  metadata: ujson.Value
)(
  using Arena,
  Context):
  private val solver          = PRCMStateCut.children(module.getOperation).head
  require(solver.getName.str == "smt.solver")
  private val allDeclarations = PRCMStateCut.children(solver).filter(_.getName.str == "smt.declare_fun")
  private val declarations    = allDeclarations.take(metadata("inputCount").num.toInt) ++
    allDeclarations.takeRight(metadata("outputCount").num.toInt)
  private val names           = metadata("symbols").arr.map(_.str).toVector
  require(names.size == declarations.size && allDeclarations.size >= names.size)
  declarations.zip(names).foreach((op, name) => op.setAttributeByName("namePrefix", name.stringAttrGet))
  val values: Map[String, Value]  = names.zip(declarations.map(_.getResult(0))).toMap
  val states: Vector[ujson.Value] = metadata("states").arr.toVector
  private val block      = solver.getRegion(0).getFirstBlock
  private val terminator = block.getTerminator
  private val location   = solver.getLocation

  private def insert(op: Operation):                                Value               =
    block.insertOwnedOperationBefore(terminator, op)
    op.getResult(0)
  def const(value: BigInt, width: Int = 1):                         Value               =
    require(value >= 0 && value.bitLength <= width && width > 0)
    (0 until width by 31).reverse
      .map: low =>
        val size = (width - low).min(31)
        insert(summon[BVConstantApi].op(((value >> low) & ((BigInt(1) << size) - 1)).toInt, size, location).operation)
      .reduce(concat)
  def equal(a:         Value, b:          Value):                   Value               = insert(summon[EqApi].op(Seq(a, b), location).operation)
  def not(a:           Value):                                      Value               = insert(summon[NotApi].op(a, location).operation)
  def and(a: Value*):                                               Value               = a match
    case Seq()      => bool(true)
    case Seq(value) => value
    case _          => insert(summon[AndApi].op(a, location).operation)
  def or(a: Seq[Value]):                                            Value               = a match
    case Seq()      => bool(false)
    case Seq(value) => value
    case _          => insert(summon[OrApi].op(a, location).operation)
  def ite(cond:        Value, a:          Value, b:      Value):    Value               = insert(summon[IteApi].op(cond, a, b, location).operation)
  def concat(a:        Value, b:          Value):                   Value               = insert(summon[ConcatApi].op(a, b, location).operation)
  def extract(a: Value, low: Int, width: Int):                      Value               =
    val tpe = summon[org.llvm.mlir.scalalib.capi.dialect.smt.TypeApi].getBitVector(width)
    insert(summon[ExtractApi].op(BigInt(low), a, location, tpe).operation)
  def bvAnd(a:         Value, b:          Value):                   Value               = insert(summon[BVAndApi].op(a, b, location).operation)
  def bvOr(a:          Value, b:          Value):                   Value               = insert(summon[BVOrApi].op(a, b, location).operation)
  def bvNot(a:         Value):                                      Value               = insert(summon[BVNotApi].op(a, location).operation)
  def asBool(a:        Value):                                      Value               = equal(a, const(1))
  def asBits(a:        Value):                                      Value               = ite(a, const(1), const(0))
  def bit(name:        String):                                     Value               = asBool(values(name))
  def bool(a:          Boolean):                                    Value               = equal(const(if a then 1 else 0), const(1))
  def select(name:     String, value:     BigInt, width: Int = 1):  Value               = equal(values(name), const(value, width))
  def registers(name: String):                                      Vector[ujson.Value] =
    states.filter(s => s("kind").str == "seq.firreg" && s("name").str == name)
  def register(name: String):                                       ujson.Value         =
    val found = registers(name)
    require(found.size == 1, s"expected one $name register")
    found.head
  def symbol(register: ujson.Value):                                String              = register("symbol").str
  def value(register:  ujson.Value):                                Value               = values(symbol(register))
  def port(register:   ujson.Value, port: String):                  Value               = values(s"${symbol(register)}_$port")
  def assume(condition: Value):                                     Unit                =
    block.insertOwnedOperationBefore(terminator, summon[AssertApi].op(condition, location).operation)
  def violation(name: String, condition: Value):                    Value               =
    val tpe  = summon[org.llvm.mlir.scalalib.capi.dialect.smt.TypeApi].getBool
    val flag = insert(summon[DeclareFunApi].op(s"violation_$name", location, tpe).operation)
    assume(equal(flag, condition))
    flag
  def query(violation: Option[Value] = None):                       SMTQuery            =
    val assertion = violation.map: condition =>
      val op = summon[AssertApi].op(condition, location).operation
      block.insertOwnedOperationBefore(terminator, op)
      op
    try
      SMTQuery.fromModule(module)
    finally
      assertion.foreach: op =>
        op.removeFromParent()
        op.destroy()

private[prcm] object PRCMRelation:
  def use[T](ir: String, metadata: ujson.Value)(body: PRCMRelation => T): T =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[SMT].loadDialect()
        val module = summon[ModuleApi].moduleCreateParse(ir)
        require(org.llvm.mlir.MlirModule.ptr(module.segment).address() != 0, "PRCM SMT parsing failed")
        try body(new PRCMRelation(module, metadata))
        finally module.destroy()
      finally summon[Context].destroy()
    finally arena.close()
