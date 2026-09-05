# rvprobe stimulus inside HAVEN's i2c testbench

Historical manual-UT replay, not the active LLM generation flow. Drivers and UTs now live in
[experiments/legacy](../../legacy/README.md); use the explicit `--legacy` harness. See the
[current framework](../../README.md) for runtime-generated UTs.

Everything but the sequence layer is HAVEN's own output (`output-*/…_i2c_master_top/final/`): copy those `.sv`/`.v`
files, `filelist.f` and `bfm_i2c_slave.sv` beside these two files, add the rvprobe sequences to the package:

```
  `include "rvprobe_i2c_bulk_seq.sv"   // one intent per register, address pinned, data randomized (x600)
  `include "rvprobe_i2c_seq.sv"        // the residual-class witnesses, replayed exactly
  `include "rvprobe_i2c_flow_seq.sv"   // I2cFlowDriver: five command flows + timing fill
  `include "rvprobe_i2c_rst_seq.sv"    // I2cFlowDriver: reset-midway intents, gap swept 0..59
  `include "rvprobe_i2c_poll_seq.sv"   // status polls (x4000)
```

then `./run.sh <tag> [+define+RVPROBE_FLOW] [+define+RVPROBE_RST]` compiles with HAVEN's VCS flags, simulates, runs
URG and scores the three DUT modules over the pinned five-metric rule (`urg_score.py`). `run.sh` expects the Synopsys
FHS wrapper at `../snps-shell` (see docs/date2027/haven-deepseek-reproduction.md).
