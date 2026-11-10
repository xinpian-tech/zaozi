// SPDX-License-Identifier: Apache-2.0
// Fixed Verilator runner: waveform capture, simulation deadline and exit status.
#include "VTestbench.h"
#include "VTestbench__Dpi.h"
#include "capi.h"  // Compiler verifies the JSON ABI against Verilator's ABI.
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <memory>

int main(int argc, char **argv) {
    auto context = std::make_unique<VerilatedContext>();
    context->threads(1);
    context->commandArgs(argc, argv);
    context->traceEverOn(true);
    auto model = std::make_unique<VTestbench>(context.get());
    VerilatedVcdC trace;
    model->trace(&trace, 99);
    trace.open("wave.vcd");
    const char *limit = std::getenv("ZAOZI_TB_MAX_TIME_NS");
    double max_ns = limit ? std::strtod(limit, nullptr) : 1000;
    if (!std::isfinite(max_ns) || max_ns <= 0) {
        std::fprintf(stderr, "ERROR: invalid simulation time limit\n");
        return 1;
    }
    long double deadline = max_ns * std::pow(10.0L, -9 - context->timeprecision());
    int status = 0;
    while (!context->gotFinish()) {
        model->eval();
        trace.dump(context->time());
        trace.flush(); // Preserve the last completed timestep if Python fails.
        if (context->gotFinish()) break;
        if (!model->eventsPending()) {
            std::fprintf(stderr, "ERROR: no pending events before $finish\n");
            status = 1;
            break;
        }
        uint64_t next = model->nextTimeSlot();
        if (static_cast<long double>(next) > deadline) {
            std::fprintf(stderr, "ERROR: simulation exceeded %.0f ns\n", max_ns);
            status = 1;
            break;
        }
        context->time(next);
    }
    model->final();
    trace.close();
    context->statsPrintSummary();
    return status;
}
