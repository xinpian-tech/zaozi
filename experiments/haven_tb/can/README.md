# rvprobe stimulus inside HAVEN's can testbench

Historical manual-UT replay, not the active LLM generation flow. Drivers and UTs now live in
[experiments/legacy](../../legacy/README.md); use the explicit `--legacy` harness. See the
[current framework](../../README.md) for runtime-generated UTs and its supported interfaces.

HAVEN's generated bench does not compile as delivered: its `seq_item` lacks the `wb_dat_o` field the template's
monitor and driver read, and its LLM-written sequences carry SystemVerilog syntax errors. Two declared edits make
the infrastructure usable: the field added to `can_top_seq_item.sv`, and the `sequence_*.sv` includes commented out
of `can_top_pkg.sv`. `can_top_test.sv` runs rvprobe's sequence under `+define+RVPROBE_FLOW`.

Run with a hard end time — `SIM_EXTRA=+vcs+finish+300000000 ./run.sh flow +define+RVPROBE_FLOW` — the bench's CAN
agent otherwise holds its objection for 10 ms of simulated time and the uncapped run was observed to block.
