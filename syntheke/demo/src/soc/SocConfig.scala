// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import upickle.default.ReadWriter

/** What this build of the demo SoC is configured as: the rates it runs at, the map it decodes, and the few numbers a
  * board would choose. Everything here could differ between two chips built from [[Soc.build]] without the topology
  * changing — which is the line: how many crossbar ports there are, which IPs exist and how wide their id spaces are
  * make this SoC what it is, and are the topology's; these are its settings.
  *
  * Serializable, like every other parameter in the framework, so a configuration can come from a file rather than only
  * from this file's defaults.
  */
@upickle.implicits.serializeDefaults(true)
case class SocConfig(
  /** The crystal on the board, and what the PLL multiplies it to for the die. */
  refHz:          Int = 25000000,
  sysHz:          Int = 100000000,
  /** DRAM. `bytes` must not exceed the device the memory model is configured as — see `sim/dram.yaml`. */
  loadBase:       Long = 0x80000000L,
  dramBytes:      Long = 0x40000000L,
  dramConfigFile: String = "dram.yaml",
  /** The peripherals, each given the same size window. */
  uartBase:       Long = 0x10000000L,
  gpioBase:       Long = 0x10010000L,
  periphSize:     Long = 0x1000L,
  baud:           Int = 115200,
  gpioWidth:      Int = 8,
  /** The DMA walks a window inside DRAM, so its base is an offset into the memory rather than an address of its own. */
  dmaOffset:      Long = 0x800L,
  dmaWindowLog2:  Int = 10,
  /** Where a debugger reaches the simulation, and how slowly the adapter clocks the TAP. */
  jtagPort:       Int = 5555,
  tckDiv:         Int = 2)
    derives ReadWriter:

  require(refHz > 0 && sysHz > 0, s"clock rates must be positive, got $refHz and $sysHz")
  require(
    dramBytes > 0 && (dramBytes & (dramBytes - 1)) == 0,
    s"DRAM size 0x${dramBytes.toHexString} must be a power of two"
  )
  require((loadBase & (dramBytes - 1)) == 0, s"DRAM base 0x${loadBase.toHexString} must be aligned to its size")
  require(periphSize > 0 && (periphSize & (periphSize - 1)) == 0, s"peripheral window must be a power of two")
  require((uartBase & (periphSize - 1)) == 0 && (gpioBase & (periphSize - 1)) == 0, "peripherals must be aligned")
  require(dmaWindowLog2 >= 4 && dmaWindowLog2 <= 30, s"DMA window 2^$dmaWindowLog2 is out of range")
  require((dmaOffset & ((1L << dmaWindowLog2) - 1)) == 0, "the DMA's window must be aligned to its size")
  require(dmaOffset + (1L << dmaWindowLog2) <= dramBytes, "the DMA's window must lie inside the memory")
  require(jtagPort > 0 && jtagPort < 65536, s"port $jtagPort must be a TCP port")

  /** Where the DMA writes: inside DRAM, derived rather than restated. */
  def dmaTarget: Long = loadBase + dmaOffset

object SocConfig:
  /** Read a configuration, or the defaults when no file is named. */
  def load(path: Option[os.Path]): SocConfig =
    path.fold(SocConfig())(p => upickle.default.read[SocConfig](os.read(p)))
