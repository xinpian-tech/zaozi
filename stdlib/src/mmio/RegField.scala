// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib.mmio

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

sealed trait RegRead

sealed trait ValueRead extends RegRead:
  final def apply(
    data: Referable[Bits]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedValueRead(ValueRead.this, data)

sealed trait ReadyValidRead extends RegRead:
  final def apply(
    ready: Referable[Bool] & Writable[Bool],
    valid: Referable[Bool],
    data:  Referable[Bits]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedReadyValidRead(ReadyValidRead.this, ready, valid, data)

sealed trait RequestResponseRead extends RegRead:
  final def apply(
    requestReady:  Referable[Bool],
    requestValid:  Referable[Bool] & Writable[Bool],
    responseReady: Referable[Bool] & Writable[Bool],
    responseValid: Referable[Bool],
    data:          Referable[Bits]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedRequestResponseRead(
      RequestResponseRead.this,
      requestReady,
      requestValid,
      responseReady,
      responseValid,
      data
    )

sealed trait RegWrite

sealed trait ValueWrite extends RegWrite:
  final def apply(
    data: Referable[Bits] & Writable[Bits]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedValueWrite(ValueWrite.this, data)

sealed trait ReadyValidWrite extends RegWrite:
  final def apply(
    ready: Referable[Bool],
    valid: Referable[Bool] & Writable[Bool],
    data:  Referable[Bits] & Writable[Bits]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedReadyValidWrite(ReadyValidWrite.this, ready, valid, data)

sealed trait RequestResponseWrite extends RegWrite:
  final def apply(
    requestReady:  Referable[Bool],
    requestValid:  Referable[Bool] & Writable[Bool],
    requestData:   Referable[Bits] & Writable[Bits],
    responseReady: Referable[Bool] & Writable[Bool],
    responseValid: Referable[Bool]
  )(
    using InstanceContext
  ): AppliedRegAccess =
    AppliedRequestResponseWrite(
      RequestResponseWrite.this,
      requestReady,
      requestValid,
      requestData,
      responseReady,
      responseValid
    )

final case class ValueReadDefinition()           extends ValueRead
final case class ReadyValidReadDefinition()      extends ReadyValidRead
final case class RequestResponseReadDefinition() extends RequestResponseRead

final case class ValueWriteDefinition()           extends ValueWrite
final case class ReadyValidWriteDefinition()      extends ReadyValidWrite
final case class RequestResponseWriteDefinition() extends RequestResponseWrite

sealed trait RegFieldDefinition:
  def name:  String
  def width: Int

  protected final def requireValidWidth(): Unit =
    require(width > 0, s"field $name width must be positive, not $width")

sealed trait RegReadDefinition extends RegFieldDefinition:
  def read: RegRead

sealed trait RegWriteDefinition extends RegFieldDefinition:
  def write: RegWrite

final case class ReservedFieldDefinition(name: String, width: Int) extends RegFieldDefinition:
  requireValidWidth()

final case class ReadFieldDefinition[R <: RegRead](name: String, width: Int, read: R)
    extends RegFieldDefinition
    with RegReadDefinition:
  requireValidWidth()

  def writeValue: ReadWriteFieldDefinition[R, ValueWriteDefinition] =
    ReadWriteFieldDefinition(name, width, read, ValueWriteDefinition())

  def writeReadyValid: ReadWriteFieldDefinition[R, ReadyValidWriteDefinition] =
    ReadWriteFieldDefinition(name, width, read, ReadyValidWriteDefinition())

  def writeRequestResponse: ReadWriteFieldDefinition[R, RequestResponseWriteDefinition] =
    ReadWriteFieldDefinition(name, width, read, RequestResponseWriteDefinition())

final case class WriteFieldDefinition[W <: RegWrite](name: String, width: Int, write: W)
    extends RegFieldDefinition
    with RegWriteDefinition:
  requireValidWidth()

final case class ReadWriteFieldDefinition[R <: RegRead, W <: RegWrite](
  name:  String,
  width: Int,
  read:  R,
  write: W)
    extends RegFieldDefinition
    with RegReadDefinition
    with RegWriteDefinition:
  requireValidWidth()

final case class RegFieldBuilder private[mmio] (name: String, width: Int):
  require(width > 0, s"field $name width must be positive, not $width")

  def readValue: ReadFieldDefinition[ValueReadDefinition] =
    ReadFieldDefinition(name, width, ValueReadDefinition())

  def readReadyValid: ReadFieldDefinition[ReadyValidReadDefinition] =
    ReadFieldDefinition(name, width, ReadyValidReadDefinition())

  def readRequestResponse: ReadFieldDefinition[RequestResponseReadDefinition] =
    ReadFieldDefinition(name, width, RequestResponseReadDefinition())

  def writeValue: WriteFieldDefinition[ValueWriteDefinition] =
    WriteFieldDefinition(name, width, ValueWriteDefinition())

  def writeReadyValid: WriteFieldDefinition[ReadyValidWriteDefinition] =
    WriteFieldDefinition(name, width, ReadyValidWriteDefinition())

  def writeRequestResponse: WriteFieldDefinition[RequestResponseWriteDefinition] =
    WriteFieldDefinition(name, width, RequestResponseWriteDefinition())

sealed trait AppliedRegAccess

private[mmio] sealed trait AppliedRegReadAccess extends AppliedRegAccess:
  def definition: RegRead

private[mmio] sealed trait AppliedRegWriteAccess extends AppliedRegAccess:
  def definition: RegWrite

private[mmio] final case class AppliedValueRead(
  definition: RegRead,
  data:       Referable[Bits])
    extends AppliedRegReadAccess

private[mmio] final case class AppliedReadyValidRead(
  definition: RegRead,
  ready:      Referable[Bool] & Writable[Bool],
  valid:      Referable[Bool],
  data:       Referable[Bits])
    extends AppliedRegReadAccess

private[mmio] final case class AppliedRequestResponseRead(
  definition:    RegRead,
  requestReady:  Referable[Bool],
  requestValid:  Referable[Bool] & Writable[Bool],
  responseReady: Referable[Bool] & Writable[Bool],
  responseValid: Referable[Bool],
  data:          Referable[Bits])
    extends AppliedRegReadAccess

private[mmio] final case class AppliedValueWrite(
  definition: RegWrite,
  data:       Referable[Bits] & Writable[Bits])
    extends AppliedRegWriteAccess

private[mmio] final case class AppliedReadyValidWrite(
  definition: RegWrite,
  ready:      Referable[Bool],
  valid:      Referable[Bool] & Writable[Bool],
  data:       Referable[Bits] & Writable[Bits])
    extends AppliedRegWriteAccess

private[mmio] final case class AppliedRequestResponseWrite(
  definition:    RegWrite,
  requestReady:  Referable[Bool],
  requestValid:  Referable[Bool] & Writable[Bool],
  requestData:   Referable[Bits] & Writable[Bits],
  responseReady: Referable[Bool] & Writable[Bool],
  responseValid: Referable[Bool])
    extends AppliedRegWriteAccess

object RegField:
  def apply(name: String, width: Int): RegFieldBuilder =
    RegFieldBuilder(name, width)

  def reserved(name: String, width: Int): ReservedFieldDefinition =
    ReservedFieldDefinition(name, width)
