# ALU residual closure on JasperGold

`experiments/alu_residual_loop.py` turns the uncovered `alu_top` lines in an URG `modinfo.txt` into a prompt,
typechecks the returned Scala, and uses JasperGold to turn each candidate intent into a concrete UVM sequence.
Before assembling the prompt it retrieves reviewed framework API excerpts from `experiments/rag/framework_api.json`
using queries about the runner's interfaces. RTL and residuals remain separate task evidence, not RAG documents.
The sequence must then be replayed under VCS/URG: a JasperGold witness proves that
the candidate input constraint is legal, while replay is what establishes coverage closure.

Generate and inspect a RAG-augmented prompt entirely offline:

```sh
python3 experiments/alu_residual_loop.py \
  --modinfo out/experiments/alu-main-baseline/urgReport/modinfo.txt \
  --out /tmp/alu-prompt-rag --prompt-only
```

Use `--rag off` as the control arm. Each run saves `rag.json` (queries, corpus version, hits, scores), plus the exact
`attempt-1/prompt.txt` and its hash/metadata in `prompt.json`. Neither mode needs a model credential. A saved model
answer can also be checked without a live model using `--response-file Generated.scala`; only live inference needs
an OpenAI-compatible provider, configured through `RVPROBE_LLM_API_KEY` and `RVPROBE_LLM_BASE_URL` (the older
`OPENAI_*` names remain aliases).

The response contains only two typed declarations: concrete `cases` and `proofObligations`. The script places them
inside its fixed JasperGold runner before typechecking, so the model cannot accidentally replace the backend or
output codec. Legacy saved responses containing a complete `object Generated` remain replayable. An obligation
records a precise suspected contradiction for a separate formal check; it does not by itself classify the line as
dead code.

RAG accepts framework APIs, types, and runner/codec interface contracts only. Historical answers, DUT operand
recipes, solver witnesses, and design-specific proof conclusions are forbidden. The loader enforces an explicit
source allowlist and checks each excerpt against its source; source hashes are retained for auditing. This does not
replace content review when framework documentation or corpus metadata changes.

Corpus version 3 also includes three generic, compiled few-shot examples in `experiments/src/rag/`: typed tuple
construction, composition of the four `Sem` kinds, and the ABI → JasperGold → outcome handling → UVM export flow.
The default queries retrieve all three alongside interface documentation (at most six records total). Inputs,
predicates and pending reasons remain caller parameters; the examples contain no design solutions. The loader reads
these approved source files directly, so the injected code is the code compiled by `experiments.compile`.
See `experiments/rag/framework_contract.md` for the examples and their boundary with the two-declaration response.

The earlier offline regression (`docs/date2027/data/alu-rag-offline-eval.json`) and 20-request online comparison
(`docs/date2027/alu-rag-evaluation.md`, `docs/date2027/data/alu-rag-live-eval.json`) used an answer-contaminated corpus
and are **invalid as evidence for framework-only RAG**. They remain historical audit artifacts, not clean results.
The old corpus is archived at `docs/date2027/data/alu-rag-contaminated-corpus.json` and is rejected by the loader.
No corrected online comparison has been run yet. Both arms must be regenerated with the current prompt for a new test.

Repeated online comparison uses `experiments/alu_rag_ablation.py`. All samples use the revised fragment contract;
the two arms differ only in retrieved context. The default is five samples per arm on each of the two historical
residuals (20 requests), one model attempt per sample, temperature 0.3. Requests run with two workers in a recorded
order; Scala/JasperGold runs serially because the harness has one shared `Generated.scala` slot.
These are two fixed residual tasks, not adaptive round-2 continuations of each newly generated round-1 sample.

```sh
python3 experiments/alu_rag_ablation.py generate --out out/experiments/alu-rag-my-run --env-file /path/to/provider.env
python3 experiments/alu_rag_ablation.py solve --out out/experiments/alu-rag-my-run
python3 experiments/alu_rag_ablation.py replay --out out/experiments/alu-rag-my-run
python3 experiments/alu_rag_ablation.py summarize --out out/experiments/alu-rag-my-run
```

The output directory must be new for `generate`; `solve` skips completed samples. Replay copies the original bench
into this run's directory, builds all successful samples together, and uses seed 1 for both same-build baselines
and all sample replays. Round 2 includes the saved historical round-1 sequence. Reports retain provider failures,
structure failures, solver outcomes, raw responses, token totals, wall times and DUT-only coverage bins. Prompt
and corpus hashes are recorded before requests; responses are never replaced with historical answers.
To overlap inference with solving, run one `solve --follow` process in a second terminal while `generate` runs.
Wait for both to finish before the final replay. The summary's `complete` field is false while any planned sample
still awaits generation, harness execution, or a successful sample's coverage replay. Failure counts remain visible;
means report their own measured-sample denominators rather than treating missing results as zero.

Report target-line closure separately from the four-metric score: an extra candidate can improve condition or toggle
bins without hitting any residual line. `proof_only_samples` counts routing decisions, not discharged proofs. The
preserved baseline scoreboard only counts transactions; this experiment measures coverage, not arithmetic correctness.
The current RAG corpus contains framework documentation only. A single-design test still cannot establish
cross-design transfer; do not mix its results with those from the retired answer-contaminated corpus.

Run the credential-free retrieval, fragment-contract, and summary-accounting tests with:

```sh
python3 -m unittest discover -s experiments -p 'test_*rag*.py' -v
nix develop . -c mill --no-server experiments.compile
nix develop . -c mill --no-server experiments.tests.testForked
```

The Scala tests exercise tuple construction, all solver outcome branches, missing ABI signals, and UVM rendering
with synthetic data outside the RAG source allowlist. The semantic composition and generic backend call chain are
typechecked; these checks do not run JasperGold or make provider requests and do not measure model effectiveness.

The 2026-09-04 run stopped with two uncovered executable lines. They are structural dead code rather than missing
stimulus. `alu_deadcode_formal.sv` states their exact path conditions as cover properties, and `prove_deadcode.tcl`
proves both unreachable:

```sh
experiments/haven_tb/eda-shell -c \
  'cd /path/to/zaozi && jg -batch -tcl experiments/haven_tb/alu/prove_deadcode.tcl -proj out/alu-deadcode-jg'
```

Expected statuses:

```text
JGSTATUS <embedded>::alu_deadcode_formal.line_336_fp_counter_default unreachable
JGSTATUS <embedded>::alu_deadcode_formal.line_401_f2i_shift_ge_32 unreachable
```
