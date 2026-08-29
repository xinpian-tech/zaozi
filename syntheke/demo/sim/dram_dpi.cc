// SPDX-License-Identifier: Apache-2.0
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
