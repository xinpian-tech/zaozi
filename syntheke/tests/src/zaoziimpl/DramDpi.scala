// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests.zaoziimpl

import me.jiuyang.zaozi.{DVBundle, HWBundle, LayerInterface, Parameter, VerilogParameter, VerilogWrapper}
import me.jiuyang.zaozi.default.{*, given}
import upickle.default.ReadWriter

/** The memory at the far end of the chip's memory port: an AXI slave whose timing is Ramulator's and whose contents are
  * a byte store beside it. [[dramDpiModel]] is the behavioral definition — the AXI handshakes and the beat counting,
  * nothing else — and [[dramDpiSource]] the DPI-C that hands each access to Ramulator and answers when it says so.
  *
  * DRAM is not something to write in RTL for a simulation. It is not on the die, it is not an IP of this design, and a
  * register file pretending to be one teaches a design nothing about the latency it will actually see. So the chip
  * publishes a memory port and the testbench terminates it in a real DRAM simulator.
  *
  * The configuration [[ddr4Config]] is Ramulator's own, exported from its Python description:
  *
  * {{{
  * import ramulator
  * frontend = ramulator.frontend.External(clock_ratio=1)
  * dram = ramulator.dram.DDR4(org_preset="DDR4_8Gb_x8", timing_preset="DDR4_2400R", rank=1)
  * ctrl = ramulator.controller.GenericDDR(
  *     dram=dram,
  *     scheduler=ramulator.scheduler.FRFCFS(),
  *     refresh_manager=ramulator.refresh_manager.AllBank(),
  *     row_policy=ramulator.row_policy.Open(),
  *     addr_mapper=ramulator.addr_mapper.RoBaRaCoCh(),
  * )
  * mem = ramulator.memory_system.GenericDRAM(
  *     clock_ratio=3, controllers=[ctrl], channel_mapper=ramulator.channel_mapper.CacheLineInterleave())
  * sim = ramulator.Simulation(frontend, mem)
  * }}}
  *
  * `python -m ramulator export ddr4.py` expands that into the fully-specified form below, which is the only form
  * Ramulator's C++ side reads. The library itself is `nix build .#ramulator`.
  */

case class DramDpiP(configFile: String, base: Long, periodPs: Long, shape: AxiShape) extends Parameter
    derives ReadWriter:
  require(configFile.nonEmpty, "the DRAM model needs a Ramulator configuration")
  require(base >= 0, s"base 0x${base.toHexString} must be non-negative")
  require(periodPs > 0, s"clock period $periodPs ps must be positive")
  require(shape.dataBits == 128, s"the DRAM port carries 128-bit beats, got ${shape.dataBits}")
  require(shape.addrBits <= 32, s"the DRAM port addresses at most a 32-bit space, got ${shape.addrBits}")
  require(base <= 0xffffffffL, s"base 0x${base.toHexString} must fit the 32-bit space the port addresses")

class DramDpiPLayers(p: DramDpiP) extends LayerInterface(p):
  def layers = Seq.empty
class DramDpiPProbe(p: DramDpiP)  extends DVBundle[DramDpiP, DramDpiPLayers](p)
// The port is the Record flavour of the AXI shape, the same one the harness's own boundary carries, so the two connect
// as whole aggregates instead of leaf by leaf.
class DramDpiIO(p: DramDpiP)      extends HWBundle(p):
  val clk = Flipped(new ClockBundle)
  val in  = Flipped(new AxiPortRecord(p.shape))

case class DramDpiVerilogP(CONFIG: String, BASE: BigInt, PERIOD_PS: BigInt, ID_W: Int, ADDR_W: Int)
    extends VerilogParameter

@generator
object DramDpi extends VerilogWrapper[DramDpiP, DramDpiPLayers, DramDpiIO, DramDpiPProbe, DramDpiVerilogP]:
  def verilogModuleName(p: DramDpiP) = "DramDpi"
  def verilogParameter(p: DramDpiP)  =
    DramDpiVerilogP(p.configFile, BigInt(p.base), BigInt(p.periodPs), p.shape.idBits, p.shape.addrBits)

/** The behavioral definition of [[DramDpi]]: an AXI slave that holds one read and one write at a time, offers each beat
  * to the model until it is taken, and answers when the model reports it done. Reads and writes are independent, so a
  * read never waits behind a write the fabric issued first.
  */
val dramDpiModel: String = """`timescale 1ns / 1ps
module DramDpi #(
    parameter CONFIG    = "dram.yaml",
    parameter BASE      = 0,
    parameter PERIOD_PS = 10000,
    parameter ID_W      = 4,
    parameter ADDR_W    = 32
) (
    input                clk_clock,
    input                clk_reset,

    input                in_aw_valid,
    output               in_aw_ready,
    input   [ID_W-1:0]   in_aw_bits_id,
    input   [ADDR_W-1:0] in_aw_bits_addr,
    input   [7:0]        in_aw_bits_len,
    input   [2:0]        in_aw_bits_size,
    input   [1:0]        in_aw_bits_burst,

    input                in_w_valid,
    output               in_w_ready,
    input   [127:0]      in_w_bits_data,
    input   [15:0]       in_w_bits_strb,
    input                in_w_bits_last,

    output               in_b_valid,
    input                in_b_ready,
    output  [ID_W-1:0]   in_b_bits_id,
    output  [1:0]        in_b_bits_resp,

    input                in_ar_valid,
    output               in_ar_ready,
    input   [ID_W-1:0]   in_ar_bits_id,
    input   [ADDR_W-1:0] in_ar_bits_addr,
    input   [7:0]        in_ar_bits_len,
    input   [2:0]        in_ar_bits_size,
    input   [1:0]        in_ar_bits_burst,

    output               in_r_valid,
    input                in_r_ready,
    output  [ID_W-1:0]   in_r_bits_id,
    output  [127:0]      in_r_bits_data,
    output  [1:0]        in_r_bits_resp,
    output               in_r_bits_last
);
  import "DPI-C" function int dram_dpi_open(input string cfg, input longint base, input longint period_ps);
  import "DPI-C" function void dram_dpi_tick();
  import "DPI-C" function int dram_dpi_write(
    input longint addr, input int d0, input int d1, input int d2, input int d3, input int strb);
  import "DPI-C" function int dram_dpi_read(input longint addr);
  import "DPI-C" function int dram_dpi_write_done();
  import "DPI-C" function int dram_dpi_read_done(output int d0, output int d1, output int d2, output int d3);

  localparam W_IDLE = 0, W_DATA = 1, W_RESP = 2;
  localparam R_IDLE = 0, R_REQ = 1, R_WAIT = 2, R_DATA = 3;

  int unsigned wstate, rstate;
  reg [63:0]   waddr, raddr;
  reg [ID_W-1:0] wid, rid;
  reg [7:0]    wlen, rlen;
  reg [7:0]    rbeat;
  reg          wheld;              // a beat stands in the buffer, not yet taken by the model
  reg [127:0]  wdata;
  reg [15:0]   wstrb;
  int unsigned wsent, wdone;       // beats handed over, and beats the model has finished
  reg [127:0]  rdata;
  int          rd0, rd1, rd2, rd3;
  int          taken;

  // A parameter carries the 32 bits of the address space this port addresses; widening it to the model's 64 must not
  // read the top bit as a sign.
  localparam longint BASE_ADDR = {32'b0, BASE[31:0]};

  initial begin
    if (dram_dpi_open(CONFIG, BASE_ADDR, PERIOD_PS) < 0)
      $fatal(1, "[DramDpi] cannot bring up the DRAM model from %s", CONFIG);
  end

  assign in_aw_ready = !clk_reset && wstate == W_IDLE;
  assign in_w_ready  = !clk_reset && wstate == W_DATA && !wheld;
  assign in_b_valid  = wstate == W_RESP && wdone == wsent;
  assign in_b_bits_id = wid;
  assign in_b_bits_resp = 2'b00;

  assign in_ar_ready = !clk_reset && rstate == R_IDLE;
  assign in_r_valid  = rstate == R_DATA;
  assign in_r_bits_id = rid;
  assign in_r_bits_data = rdata;
  assign in_r_bits_resp = 2'b00;
  assign in_r_bits_last = rbeat == rlen;

  always @(posedge clk_clock) begin
    if (clk_reset) begin
      wstate <= W_IDLE;
      rstate <= R_IDLE;
      wheld  <= 1'b0;
      wsent  <= 0;
      wdone  <= 0;
      rbeat  <= 8'b0;
    end else begin
      // ---- writes: take the address, then one beat at a time, then answer once the model has them all
      case (wstate)
        W_IDLE:
          if (in_aw_valid) begin
            waddr  <= {{(64 - ADDR_W){1'b0}}, in_aw_bits_addr};
            wid    <= in_aw_bits_id;
            wlen   <= in_aw_bits_len;
            wsent  <= 0;
            wdone  <= 0;
            wstate <= W_DATA;
          end
        W_DATA: begin
          if (wheld) begin
            taken = dram_dpi_write(waddr, wdata[31:0], wdata[63:32], wdata[95:64], wdata[127:96],
                                   {16'b0, wstrb});
            if (taken != 0) begin
              wheld <= 1'b0;
              waddr <= waddr + 64'd16;
              wsent <= wsent + 1;
              if (wsent == {24'b0, wlen}) wstate <= W_RESP;
            end
          end else if (in_w_valid) begin
            wdata <= in_w_bits_data;
            wstrb <= in_w_bits_strb;
            wheld <= 1'b1;
          end
        end
        W_RESP:
          if (in_b_valid && in_b_ready) wstate <= W_IDLE;
      endcase
      // Completions arrive whenever Ramulator finishes one; between the address and the response they are counted.
      if (wstate != W_IDLE) wdone <= wdone + dram_dpi_write_done();

      // ---- reads: one beat in flight, streamed back as the model finishes each
      case (rstate)
        R_IDLE:
          if (in_ar_valid) begin
            raddr  <= {{(64 - ADDR_W){1'b0}}, in_ar_bits_addr};
            rid    <= in_ar_bits_id;
            rlen   <= in_ar_bits_len;
            rbeat  <= 8'b0;
            rstate <= R_REQ;
          end
        R_REQ:
          if (dram_dpi_read(raddr) != 0) rstate <= R_WAIT;
        R_WAIT:
          if (dram_dpi_read_done(rd0, rd1, rd2, rd3) != 0) begin
            rdata  <= {rd3, rd2, rd1, rd0};
            rstate <= R_DATA;
          end
        R_DATA:
          if (in_r_ready) begin
            if (rbeat == rlen) begin
              rstate <= R_IDLE;
            end else begin
              rbeat  <= rbeat + 8'd1;
              raddr  <= raddr + 64'd16;
              rstate <= R_REQ;
            end
          end
      endcase

      // The DRAM's own clock runs at whatever ratio its timing implies; one bus clock is that many of its cycles.
      dram_dpi_tick();
    end
  end
endmodule
"""

/** The DPI-C behind [[dramDpiModel]]: Ramulator for the timing, a sparse byte store for the contents. Ramulator models
  * when an access finishes, not what it returns — a memory simulator is not a memory — so the bytes live here and the
  * completion callback is what releases them.
  */
val dramDpiSource: String = """// SPDX-License-Identifier: Apache-2.0
// The simulation side of the memory port: every access goes to Ramulator for its timing and to the store for its data.
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <unordered_map>

#include <ramulator/base/config.h>
#include <ramulator/base/factory.h>
#include <ramulator/base/request.h>
#include <ramulator/frontend/i_frontend.h>
#include <ramulator/memory_system/i_memory_system.h>

namespace {

Ramulator::IFrontEnd* frontend = nullptr;
Ramulator::IMemorySystem* memory = nullptr;

// Where the fabric decodes this memory, so Ramulator sees an offset into the device it models.
uint64_t base_addr = 0;
// How many DRAM cycles pass in one bus clock, from Ramulator's own tCK and the port's settled period.
int ticks_per_clock = 1;
// One beat, the granularity of both the store and a request.
constexpr int BEAT = 16;

std::unordered_map<uint64_t, std::array<uint8_t, BEAT>> store;

bool write_busy = false;
int write_done = 0;
bool read_busy = false;
bool read_ready = false;
uint64_t read_addr = 0;

} // namespace

extern "C" int dram_dpi_open(const char* config, long long base, long long period_ps) {
  try {
    auto cfg = Ramulator::Config::parse_config_file(config);
    frontend = Ramulator::Factory::create_frontend(cfg);
    memory = Ramulator::Factory::create_memory_system(cfg);
    frontend->connect_memory_system(memory);
    memory->connect_frontend(frontend);
  } catch (const std::exception& e) {
    fprintf(stderr, "[DramDpi] %s: %s\n", config, e.what());
    return -1;
  }
  if (frontend == nullptr || memory == nullptr) {
    fprintf(stderr, "[DramDpi] %s does not describe a frontend and a memory system\n", config);
    return -1;
  }

  base_addr = (uint64_t)base;
  const float tCK_ns = memory->get_tCK();
  ticks_per_clock = tCK_ns > 0.0f ? (int)std::llround((double)period_ps / ((double)tCK_ns * 1000.0)) : 1;
  if (ticks_per_clock < 1) ticks_per_clock = 1;
  fprintf(stderr, "[DramDpi] %s at %#llx: tCK %.3f ns, %d DRAM cycles per bus clock, %d bytes per transaction\n",
          config, (unsigned long long)base_addr, (double)tCK_ns, ticks_per_clock, memory->get_tx_bytes());
  return ticks_per_clock;
}

extern "C" void dram_dpi_tick() {
  for (int i = 0; i < ticks_per_clock; i++) memory->tick();
}

extern "C" int dram_dpi_write(long long addr, int d0, int d1, int d2, int d3, int strb) {
  if (write_busy) return 0;

  const uint64_t beat = (uint64_t)addr & ~(uint64_t)(BEAT - 1);
  // The data is ours the moment the beat arrives; Ramulator only says when the access is over.
  const uint32_t words[4] = {(uint32_t)d0, (uint32_t)d1, (uint32_t)d2, (uint32_t)d3};
  auto& block = store[beat];
  for (int b = 0; b < BEAT; b++)
    if (strb & (1 << b)) block[b] = (uint8_t)(words[b / 4] >> (8 * (b % 4)));

  write_busy = true;
  const bool sent = frontend->receive_external_requests(
      Ramulator::Request::Type::Write, (Ramulator::Addr_t)(beat - base_addr), 0,
      [](Ramulator::Request&) {
        write_busy = false;
        write_done++;
      },
      BEAT);
  if (!sent) write_busy = false; // the queue is full: the beat stands and is offered again next cycle
  return sent ? 1 : 0;
}

extern "C" int dram_dpi_write_done() {
  const int done = write_done;
  write_done = 0;
  return done;
}

extern "C" int dram_dpi_read(long long addr) {
  if (read_busy || read_ready) return 0;

  read_addr = (uint64_t)addr & ~(uint64_t)(BEAT - 1);
  read_busy = true;
  const bool sent = frontend->receive_external_requests(
      Ramulator::Request::Type::Read, (Ramulator::Addr_t)(read_addr - base_addr), 0,
      [](Ramulator::Request&) {
        read_busy = false;
        read_ready = true;
      },
      BEAT);
  if (!sent) read_busy = false;
  return sent ? 1 : 0;
}

extern "C" int dram_dpi_read_done(int* d0, int* d1, int* d2, int* d3) {
  if (!read_ready) return 0;
  read_ready = false;

  // A block nobody has written reads as zero, the way a memory nobody has loaded does.
  std::array<uint8_t, BEAT> block{};
  const auto found = store.find(read_addr);
  if (found != store.end()) block = found->second;

  uint32_t words[4] = {0, 0, 0, 0};
  for (int b = 0; b < BEAT; b++) words[b / 4] |= (uint32_t)block[b] << (8 * (b % 4));
  *d0 = (int)words[0];
  *d1 = (int)words[1];
  *d2 = (int)words[2];
  *d3 = (int)words[3];
  return 1;
}
"""

/** Ramulator's own configuration, exported from the Python description in [[DramDpi]]'s documentation: one channel of
  * DDR4-2400, 8Gb x8, one rank — 1 GiB, which is the address range the memory port publishes.
  */
val ddr4Config: String = """frontend:
  impl: External
  clock_ratio: 1
memory_system:
  impl: GenericDRAM
  clock_ratio: 3
  channel_mapper:
    impl: CacheLineInterleave
    interleave_bits: 0
  controllers:
    - impl: GenericDDR
      wr_low_watermark: 0.2
      wr_high_watermark: 0.8
      read_buffer_size: 32
      write_buffer_size: 32
      priority_buffer_size: 1568
      scheduler:
        impl: FRFCFS
      refresh_manager:
        impl: AllBank
        scatter_interval: 0
        debug: false
      row_policy:
        impl: Open
      addr_mapper:
        impl: RoBaRaCoCh
      dram:
        impl: DDR4
        org:
          dq: 8
          count: [1, 1, 4, 4, 65536, 1024]
        timing: [2400, 4, 16, 16, 16, 39, 55, 18, 9, 12, 4, 6, 4, 6, 3, 9, 26, 433, 9364, 2, 833]
        command_cycles: [1, 1, 1, 1, 1, 1, 1, 1]
        channel_width: 64
        read_latency: 20
        timing_constraints:
          - [0, [3, 5], [3, 5], 4]
          - [0, [4, 6], [4, 6], 4]
          - [1, [3, 5], [3, 5], 4]
          - [1, [4, 6], [4, 6], 4]
          - [1, [3, 5], [4, 6], 10]
          - [1, [4, 6], [3, 5], 19]
          - [1, [3, 5], [3, 4, 5, 6], 6, 1, true]
          - [1, [4, 6], [3, 5], 2, 1, true]
          - [1, [3], [2], 9]
          - [1, [4], [2], 34]
          - [1, [0], [0], 4]
          - [1, [0], [0], 26, 4]
          - [1, [0], [2], 39]
          - [1, [2], [0], 16]
          - [1, [0], [7], 55]
          - [1, [1, 2], [7], 16]
          - [1, [5], [7], 25]
          - [1, [6], [7], 50]
          - [1, [7], [0, 2], 433]
          - [2, [3, 5], [3, 5], 6]
          - [2, [4, 6], [4, 6], 6]
          - [2, [4, 6], [3, 5], 25]
          - [2, [0], [0], 6]
          - [3, [0], [0], 55]
          - [3, [0], [3, 4, 5, 6], 16]
          - [3, [0], [1], 39]
          - [3, [1], [0], 16]
          - [3, [3], [1], 9]
          - [3, [4], [1], 34]
          - [3, [5], [0], 25]
          - [3, [6], [0], 50]
"""
