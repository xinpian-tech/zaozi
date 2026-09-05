# rvprobe stimulus inside HAVEN's sdram testbench

Historical manual-UT replay, not the active LLM generation flow. Drivers and UTs now live in
[experiments/legacy](../../legacy/README.md); the current single-clock generator does not claim
to support this benchmark's clock mapping automatically.

Copy HAVEN's generated bench (`output-*/…_sdrc_top/final/*.sv`, `bfm_sdram_model.sv`) and the RTL beside these
files. Two edits to HAVEN's output, both declared: the SDRAM clock — `logic sdram_clk` in the interface is never
driven by the generated bench (the seq_item lists it as a random bit), so `assign vif.sdram_clk = clk;` goes after
the interface instantiation in `sdrc_top_top.sv` — and `sdrc_top_pkg.sv` includes `rvprobe_sdram_flow_seq.sv`.
`sdrc_top_test.sv` runs HAVEN's own sequence list by default and rvprobe's under `+define+RVPROBE_FLOW`.
`./run.sh <tag> [+define+RVPROBE_FLOW]` compiles, simulates, and scores the ten DUT modules over the five-metric rule.
