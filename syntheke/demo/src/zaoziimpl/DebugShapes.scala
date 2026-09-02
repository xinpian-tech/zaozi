// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bundle, Record}

/** The zaozi shapes of the debug subsystem's three protocols. Like the AXI shapes they come in two flavours — the
  * string-keyed Records of `AxiShapes.scala` for a module whose ports are named by its parameter (the debug module has
  * one port per hart), the typed Bundles of `AxiBundles.scala` for a module with a fixed port list.
  */

/** One JTAG TAP, from the TAP's side: driven and clocked from outside, answering on `tdo`. */
class JtagBundle extends Bundle:
  val tck   = Flipped(Clock())
  val tms   = Flipped(Bool())
  val tdi   = Flipped(Bool())
  val trstN = Flipped(Bool())
  val tdo   = Aligned(Bool())

class JtagRecord extends Record:
  val tck   = Flipped("tck", Clock())
  val tms   = Flipped("tms", Bool())
  val tdi   = Flipped("tdi", Bool())
  val trstN = Flipped("trstN", Bool())
  val tdo   = Aligned("tdo", Bool())

/** One DMI request/response pair, from the transport's side. */
class DmiReqBits(abits: Int, dataBits: Int) extends Record:
  val addr = Aligned("addr", Bits(abits))
  val data = Aligned("data", Bits(dataBits))
  val op   = Aligned("op", Bits(2))

class DmiRespBits(dataBits: Int) extends Record:
  val data = Aligned("data", Bits(dataBits))
  val op   = Aligned("op", Bits(2))

class DmiRecord(abits: Int, dataBits: Int) extends Record:
  val req  = Aligned("req", new Channel(new DmiReqBits(abits, dataBits)))
  val resp = Flipped("resp", new Channel(new DmiRespBits(dataBits)))

class DmiReqBundle(abits: Int, dataBits: Int) extends Bundle:
  val addr = Aligned(Bits(abits))
  val data = Aligned(Bits(dataBits))
  val op   = Aligned(Bits(2))

class DmiRespBundle(dataBits: Int) extends Bundle:
  val data = Aligned(Bits(dataBits))
  val op   = Aligned(Bits(2))

class DmiBundle(abits: Int, dataBits: Int) extends Bundle:
  val req  = Aligned(new ChannelBundle(new DmiReqBundle(abits, dataBits)))
  val resp = Flipped(new ChannelBundle(new DmiRespBundle(dataBits)))

/** One debug module port on one hart, from the debug module's side: the halt request — the RISC-V debug interrupt —
  * with the resume and reset requests, the abstract command channel, and the hart's status back.
  */
class DebugCmd(xlen: Int) extends Record:
  val valid   = Aligned("valid", Bool())
  val kind    = Aligned("kind", Bits(2))
  val write   = Aligned("write", Bool())
  val regno   = Aligned("regno", Bits(16))
  val size    = Aligned("size", Bits(3))
  val data    = Aligned("data", Bits(xlen))
  val address = Aligned("address", Bits(xlen))

class DebugStatus(xlen: Int) extends Record:
  val halted    = Aligned("halted", Bool())
  val running   = Aligned("running", Bool())
  val resumeAck = Aligned("resumeAck", Bool())
  val resetAck  = Aligned("resetAck", Bool())
  val cmdDone   = Aligned("cmdDone", Bool())
  val cmdError  = Aligned("cmdError", Bits(3))
  val cmdRdata  = Aligned("cmdRdata", Bits(xlen))

class DebugHartRecord(xlen: Int) extends Record:
  val halt        = Aligned("halt", Bool())
  val resume      = Aligned("resume", Bool())
  val reset       = Aligned("reset", Bool())
  val haltOnReset = Aligned("haltOnReset", Bool())
  val cmd         = Aligned("cmd", new DebugCmd(xlen))
  val hart        = Flipped("hart", new DebugStatus(xlen))

class DebugCmdBundle(xlen: Int) extends Bundle:
  val valid   = Aligned(Bool())
  val kind    = Aligned(Bits(2))
  val write   = Aligned(Bool())
  val regno   = Aligned(Bits(16))
  val size    = Aligned(Bits(3))
  val data    = Aligned(Bits(xlen))
  val address = Aligned(Bits(xlen))

class DebugStatusBundle(xlen: Int) extends Bundle:
  val halted    = Aligned(Bool())
  val running   = Aligned(Bool())
  val resumeAck = Aligned(Bool())
  val resetAck  = Aligned(Bool())
  val cmdDone   = Aligned(Bool())
  val cmdError  = Aligned(Bits(3))
  val cmdRdata  = Aligned(Bits(xlen))

class DebugHartBundle(xlen: Int) extends Bundle:
  val halt        = Aligned(Bool())
  val resume      = Aligned(Bool())
  val reset       = Aligned(Bool())
  val haltOnReset = Aligned(Bool())
  val cmd         = Aligned(new DebugCmdBundle(xlen))
  val hart        = Flipped(new DebugStatusBundle(xlen))
