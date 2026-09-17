package me.jiuyang.syntheke.demo

import upickle.default.ReadWriter

@upickle.implicits.serializeDefaults(true)
case class SocConfig(
  refHz:           Int = 25000000,
  sysHz:           Int = 100000000,
  loadBase:        Long = 0x80000000L,
  dramBytes:       Long = 0x40000000L,
  dramConfigFile:  String = "dram.yaml",
  uartBase:        Long = 0x10000000L,
  gpioBase:        Long = 0x10010000L,
  powerBase:       Long = 0x10020000L,
  iomuxBase:       Long = 0x10030000L,
  periphSize:      Long = 0x1000L,
  baud:            Int = 115200,
  gpioWidth:       Int = 8,
  uartPins:        Vector[Int] = Vector(0, 1),
  jtagPins:        Vector[Int] = Vector(2, 3, 4, 5),
  dmaOffset:       Long = 0x800L,
  dmaWindowLog2:   Int = 10,
  jtagPort:        Int = 5555,
  tckDiv:          Int = 2)
    derives ReadWriter:

  require(refHz > 0 && sysHz > 0, s"clock rates must be positive, got $refHz and $sysHz")
  require(
    dramBytes > 0 && (dramBytes & (dramBytes - 1)) == 0,
    s"DRAM size 0x${dramBytes.toHexString} must be a power of two"
  )
  require((loadBase & (dramBytes - 1)) == 0, s"DRAM base 0x${loadBase.toHexString} must be aligned to its size")
  require(periphSize > 0 && (periphSize & (periphSize - 1)) == 0, s"peripheral window must be a power of two")
  require(Seq(uartBase, gpioBase, powerBase, iomuxBase).forall(base => (base & (periphSize - 1)) == 0), "peripherals must be aligned")
  require(gpioWidth >= 1 && gpioWidth <= 32, "GPIO width must be within 1..32")
  require(uartPins.size == 2 && jtagPins.size == 4, "UART needs TX/RX pins and JTAG needs TMS/TDI/TRSTn/TDO pins")
  require((uartPins ++ jtagPins).forall(_ >= 0), "board pins must be nonnegative")
  require((uartPins ++ jtagPins).distinct.size == 6, "UART and JTAG must have distinct reset routes")
  require(dmaWindowLog2 >= 4 && dmaWindowLog2 <= 30, s"DMA window 2^$dmaWindowLog2 is out of range")
  require((dmaOffset & ((1L << dmaWindowLog2) - 1)) == 0, "the DMA's window must be aligned to its size")
  require(dmaOffset + (1L << dmaWindowLog2) <= dramBytes, "the DMA's window must lie inside the memory")
  require(jtagPort > 0 && jtagPort < 65536, s"port $jtagPort must be a TCP port")

  def dmaTarget: Long = loadBase + dmaOffset
  def pinCount: Int = math.max(gpioWidth, (uartPins ++ jtagPins).max + 1)

object SocConfig:
  def load(path: Option[os.Path]): SocConfig =
    path.fold(SocConfig())(p => upickle.default.read[SocConfig](os.read(p)))
