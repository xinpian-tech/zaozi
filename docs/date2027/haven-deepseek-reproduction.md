# HAVEN reproduced on DeepSeek V4 Flash (2026-08-28)

A full reproduction of HAVEN's 16-design benchmark (github.com/mcc311/haven, MIT) with
`deepseek-v4-flash-vision-exp` swapped in for the paper's `gpt-5.2` / `gpt-5.2-codex`, run on
172.24.6.39 against Synopsys VCS V-2023.12 + URG. Purpose: (a) an independent baseline for the A2 arm
of our experiment plan, and (b) the strong-vs-weak-model contrast our own typed-DSL arm claims to
compensate for.

## Setup

- **Pipeline**: unmodified HAVEN phases 0–7 (`--until 7`), the author's own 4-group parallel layout
  from `scripts/run_16.sh` with `+vcs+lic+wait`.
- **Model**: `deepseek-v4-flash-vision-exp`, temperature 0.3, OpenAI-compatible endpoint. It is a
  *thinking* model — completion tokens are dominated by reasoning tokens.
- **EDA**: VCS/URG reached through a `buildFHSEnv` wrapper (`snps-fhs-env` from the T1 tree); HAVEN's
  `eda_env.shell` config hook routes every EDA command through it, so no HAVEN EDA code changed.

## Results (16 designs)

| Design | DeepSeek V4 Flash | HAVEN paper (GPT) | Δ |
|---|---|---|---|
| aes | **96.5** | 96.5 | ±0.0 |
| alu | **95.9** | 96.1 | −0.2 |
| sha3 (keccak) | **95.1** | 95.7 | −0.6 |
| spi | **93.5** | 93.1 | **+0.4** |
| uart | **88.4** | 87.2 | **+1.2** |
| i2c | 63.2 | 90.0 | −26.8 |
| sdram | 11.0 | 70.0 | −59.0 |
| can | *compile failed* | 90.6 | — |
| ethmac | *compile failed* | 69.7 | — |
| gpio | 93.5 | — | — |
| ue_timer | 93.4 | — | — |
| simple_spi | 88.5 | — | — |
| ue_gpio | 84.1 | — | — |
| ue_uart | 83.5 | — | — |
| axil_ram | 82.0 | — | — |
| ue_spi | *compile failed* | — | — |

Coverage is URG total (line/cond/toggle/branch), best iteration of the Phase 6–7 loop.

## The finding: a bimodal split, not a uniform gap

- **Compile success 13/16 = 81.2%** vs HAVEN's reported **100%**.
- **Comparable subset (n=7)**: DeepSeek 77.7 vs GPT 89.8 → **−12.1 points**.
- **Same subset minus the two collapse cases (n=5)**: DeepSeek **93.87** vs GPT **93.72** → **+0.15**.

The average gap is *entirely* concentrated in complex designs. On aes/alu/sha3/spi/uart the weak model
is statistically indistinguishable from the frontier model; on i2c, sdram, can, ethmac, ue_spi it
either collapses (sdram 11%) or never reaches a compiling testbench. The dividing line is long-range
state-machine reasoning: template-shaped work transfers to a cheap model, multi-step architectural
reasoning does not.

**Cost**: 5.85M tokens ≈ **¥0.29** for the entire 16-design sweep (3h10m wall-clock, 4-way parallel).
The GPT-equivalent is 2–3 orders of magnitude more expensive.

## Why this matters for our paper

The failure mode is exactly the one our contribution addresses. HAVEN's DSL is a *structural* safety
boundary (JSON steps → Jinja2 templates): it prevents the LLM from writing raw HDL, but nothing checks
that the emitted testbench is semantically coherent, so a weak model's errors surface only at VCS
compile time — after which HAVEN's repair loop exhausts 5 attempts and gives up (can, ue_spi, ethmac).
Our typed layer moves that boundary earlier: an ill-formed intent fails at `scalac` with a located
message that feeds the repair loop, and a well-typed intent cannot produce an incoherent stimulus
because the solver, not the model, constructs it. The prediction to test in our own experiments is
that the strong/weak gap *narrows* under the typed DSL relative to the template DSL — this table is
the baseline that makes that claim measurable.

## Reproduction deviations (all forced, all documented)

1. **Structured output**: DeepSeek's thinking models reject a forced `tool_choice`, so HAVEN's
   `with_structured_output(method="function_calling")` 400s. Switched to `json_mode` with the JSON
   Schema injected into the prompt, plus a salvage path that extracts the outermost JSON object from
   raw content when parsing fails.
2. **Reference models dropped**: every `hdl/*/haven.json` points `ref_model.c_file` at an absolute
   path on the author's NAS (`/mnt/nas/homes/...`), and the `ref/*.c` sources are not in the repo.
   All 16 designs therefore ran scoreboard-less. Coverage metrics are unaffected; the paper's
   functional-correctness column cannot be reproduced from the public repo.
3. **`coverage_improver` None-guard**: `_generate_bo_template` assumed structured output is never
   `None` (true under function calling, not under JSON mode) and crashed on `result.params`.
4. **`testbench_generator.py:1108` width bug** — a genuine HAVEN bug, not a DeepSeek artifact:
   `f['width'] > 1` raises `TypeError` when the model returns a width as `"32"` instead of `32`. The
   code assumes strict schema adherence that only a strong model reliably provides; a weak model
   crashes the pipeline outright (ethmac). Patched with `int()` coercion.
5. **sha3 missing from the sweep script**: `scripts/run_16.sh` lists only 15 of the 16 designs. Run
   separately here.

Artifacts: `scratchpad/haven/output-deepseek/` (per-run IR, coverage progressions, token metrics),
`logs-deepseek/` (per-design logs), `collect_deepseek.py` (the table generator).
