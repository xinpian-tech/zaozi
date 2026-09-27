// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

#include <verilated.h>
#include <verilated_cov.h>
#include <verilated_vcd_c.h>

#include <cstdio>
#include <cstdlib>
#include <memory>

// The backend supplies the generated V<top>.h through -include.
using Testbench = ZAOZI_TOP;

int main(int argc, char **argv) {
  if (argc != 1) {
    std::fprintf(stderr, "usage: %s\n", argv[0]);
    return 1;
  }
  auto context = std::make_unique<VerilatedContext>();
  context->commandArgs(1, argv);
  context->timeunit(-9);
  context->timeprecision(-12);
  context->traceEverOn(true);
  auto top = std::make_unique<Testbench>(context.get());
  auto trace = std::make_unique<VerilatedVcdC>();
  top->trace(trace.get(), 99);
  trace->open("trace.vcd");

  top->eval();
  trace->dump(context->time());

  for (unsigned tick = 0;
       tick < 200000 && !context->gotFinish() && top->eventsPending(); ++tick) {
    context->time(top->nextTimeSlot());
    top->eval();
    trace->dump(context->time());
  }
  const bool finished = context->gotFinish();
  top->final();
  trace->close();
  VerilatedCov::write("coverage.dat");
  return finished ? 0 : 1;
}
