// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
//
// The whole of it: hold the two objects Ramulator wants connected to each other, and turn its completion callback into
// a queue something else can poll.

#include "ramulator_capi.h"

#include <cstdio>
#include <deque>
#include <exception>
#include <memory>
#include <string>

#include <ramulator/base/config.h>
#include <ramulator/base/factory.h>
#include <ramulator/base/request.h>
#include <ramulator/frontend/i_frontend.h>
#include <ramulator/memory_system/i_memory_system.h>

struct ramulator_t {
  Ramulator::IFrontEnd *frontend = nullptr;
  Ramulator::IMemorySystem *memory = nullptr;
  std::deque<uint64_t> done;
};

extern "C" ramulator_t *ramulator_open(const char *config_path) {
  auto r = std::make_unique<ramulator_t>();
  try {
    auto config = Ramulator::Config::parse_config_file(config_path);
    r->frontend = Ramulator::Factory::create_frontend(config);
    r->memory = Ramulator::Factory::create_memory_system(config);
  } catch (const std::exception &e) {
    fprintf(stderr, "[ramulator] %s: %s\n", config_path, e.what());
    return nullptr;
  }
  if (r->frontend == nullptr || r->memory == nullptr) {
    fprintf(stderr, "[ramulator] %s describes no frontend or no memory system\n", config_path);
    return nullptr;
  }
  r->frontend->connect_memory_system(r->memory);
  r->memory->connect_frontend(r->frontend);
  return r.release();
}

extern "C" void ramulator_close(ramulator_t *r) {
  if (r == nullptr) return;
  r->frontend->finalize();
  r->memory->finalize();
  delete r;
}

extern "C" double ramulator_tck_ns(const ramulator_t *r) { return r->memory->get_tCK(); }

extern "C" int32_t ramulator_tx_bytes(const ramulator_t *r) { return r->memory->get_tx_bytes(); }

extern "C" void ramulator_tick(ramulator_t *r) { r->memory->tick(); }

extern "C" int32_t ramulator_send(ramulator_t *r, int32_t is_write, uint64_t addr, int32_t size_bytes, uint64_t tag) {
  const int type = is_write ? Ramulator::Request::Type::Write : Ramulator::Request::Type::Read;
  auto *queue = &r->done;
  const bool sent = r->frontend->receive_external_requests(
      type, (Ramulator::Addr_t)addr, 0, [queue, tag](Ramulator::Request &) { queue->push_back(tag); }, size_bytes);
  return sent ? 1 : 0;
}

extern "C" int32_t ramulator_poll(ramulator_t *r, uint64_t *tag) {
  if (r->done.empty()) return 0;
  *tag = r->done.front();
  r->done.pop_front();
  return 1;
}
