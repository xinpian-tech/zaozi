// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.attr

import org.llvm.mlir.scalalib.capi.ir.{Attribute, Context}
import java.lang.foreign.Arena

enum EventControl:
  case PosEdge, NegEdge, Edge

  def attrValue: Long = this match
    case PosEdge => 0L
    case NegEdge => 1L
    case Edge    => 2L
end EventControl

enum ResetStyle:
  case NoReset, SyncReset, AsyncReset

  def attrValue: Long = this match
    case NoReset    => 0L
    case SyncReset  => 1L
    case AsyncReset => 2L
end ResetStyle

enum CaseStyle:
  case Case, CaseX, CaseZ

  def attrValue: Long = this match
    case Case  => 0L
    case CaseX => 1L
    case CaseZ => 2L
end CaseStyle

enum DeferAssert:
  case Immediate, Observed, Final

  def attrValue: Long = this match
    case Immediate => 0L
    case Observed  => 1L
    case Final     => 2L
end DeferAssert

enum MemoryBase:
  case Binary, Hexadecimal

  def attrValue: Long = this match
    case Binary      => 0L
    case Hexadecimal => 1L
end MemoryBase

enum Verbosity:
  case Zero, One, Two

  def attrValue: Long = this match
    case Zero => 0L
    case One  => 1L
    case Two  => 2L
end Verbosity

enum ValidationQualifier:
  case Plain, Unique, Unique0, Priority

  def attrValue: String = this match
    case Plain    => "plain"
    case Unique   => "unique"
    case Unique0  => "unique0"
    case Priority => "priority"
end ValidationQualifier

enum IncludeStyle:
  case Local, System

  def attrValue: String = this match
    case Local  => "local"
    case System => "system"
end IncludeStyle

enum ModportDirection:
  case Input, Output, InOut

  def attrValue: String = this match
    case Input  => "input"
    case Output => "output"
    case InOut  => "inout"
end ModportDirection

trait AttributeApi:
  extension (ref: EventControl)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: ResetStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: CaseStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: DeferAssert)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: MemoryBase)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: Verbosity)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: ValidationQualifier)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: IncludeStyle)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  extension (ref: ModportDirection)
    def toAttribute(
      using Arena,
      Context
    ): Attribute
  def getMacroIdent(
    name: String
  )(
    using Arena,
    Context
  ):   Attribute
  def getModportStruct(
    direction: ModportDirection,
    signal:    String
  )(
    using Arena,
    Context
  ):   Attribute
  def getCaseExprPattern(
    using Arena,
    Context
  ):   Attribute
  def getCaseDefault(
    using Arena,
    Context
  ):   Attribute

  /** Bits are written most significant first, using 0, 1, x or z. */
  def getCasePattern(
    bits: String
  )(
    using Arena,
    Context
  ): Attribute
end AttributeApi
