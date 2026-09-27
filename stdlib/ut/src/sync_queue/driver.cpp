// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

#include "zaozi_dpi.hpp"

#include <nlohmann/json.hpp>

#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <stdexcept>
#include <vector>

namespace {

struct Stimulus {
  unsigned resetN;
  unsigned pushRequestN;
  unsigned popRequestN;
  unsigned diagnosticN;
  unsigned dataIn;
};

void from_json(const nlohmann::json &value, Stimulus &stimulus) {
  value.at("reset_n").get_to(stimulus.resetN);
  value.at("push_n").get_to(stimulus.pushRequestN);
  value.at("pop_n").get_to(stimulus.popRequestN);
  value.at("diagnostic_n").get_to(stimulus.diagnosticN);
  value.at("data_in").get_to(stimulus.dataIn);
}

const std::vector<Stimulus> &vectors() {
  static const std::vector<Stimulus> value = [] {
    try {
      const char *path = std::getenv("ZAOZI_STIMULUS_JSON");
      if (!path)
        throw std::runtime_error("ZAOZI_STIMULUS_JSON is not set");
      std::ifstream stream(path);
      if (!stream)
        throw std::runtime_error("cannot open stimulus JSON");
      const auto document = nlohmann::json::parse(stream);
      auto result = document.at("vectors").get<std::vector<Stimulus>>();
      if (result.empty())
        throw std::runtime_error("stimulus vector list is empty");
      for (const auto &stimulus : result) {
        if (stimulus.resetN > 1 || stimulus.pushRequestN > 1 ||
            stimulus.popRequestN > 1 || stimulus.diagnosticN > 1 ||
            stimulus.dataIn > 255)
          throw std::runtime_error("stimulus value is out of range");
      }
      return result;
    } catch (const std::exception &error) {
      std::fprintf(stderr, "invalid stimulus JSON: %s\n", error.what());
      std::abort();
    }
  }();
  return value;
}

std::size_t cycle = 0;

} // namespace

extern "C" int zaozi_step(svBit *resetN, svBit *pushRequestN,
                          svBit *popRequestN, svBit *diagnosticN, char *dataIn,
                          svBit *done) {
  static const Stimulus idle = {1, 1, 1, 1, 0};
  const auto &commands = vectors();
  const Stimulus &stimulus = cycle < commands.size() ? commands[cycle] : idle;

  *resetN = static_cast<svBit>(stimulus.resetN);
  *pushRequestN = static_cast<svBit>(stimulus.pushRequestN);
  *popRequestN = static_cast<svBit>(stimulus.popRequestN);
  *diagnosticN = static_cast<svBit>(stimulus.diagnosticN);
  *dataIn = static_cast<char>(stimulus.dataIn);
  // Let the queue observe three idle cycles before terminating the wrapper.
  *done = cycle >= commands.size() + 3;
  ++cycle;
  return 0;
}
