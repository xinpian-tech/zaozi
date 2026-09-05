# ALU 动态意图实验

通用架构和无模型复现流程见 [experiments/README](../../README.md)，输出契约见
[PROMPT](../../PROMPT.md)。当前 ALU 只维护 [端口 manifest](../../designs/alu.json)；
原始 RTL 在 [fixtures/haven/alu_top.v](../../fixtures/haven/alu_top.v)，没有活动的 `HavenAlu*UT` 依赖。

## 任务输入与模型调用

在仓库根目录生成并检查 prompt，不需要模型密钥：

```sh
python3 experiments/sequence_experiment.py \
  --design experiments/designs/alu.json \
  --modinfo out/experiments/alu-main-baseline/urgReport/modinfo.txt \
  --out out/experiments/alu-runtime-prompt --prompt-only
```

输出目录必须是新的。用 `--rag off` 建立对照。每轮保存 prompt、哈希和检索 provenance。
本地 response 使用 `--response-file /path/to/response.json`；可加 `--prepare-only` 或
`--compile-only`。不再接受旧的两段 Scala 列表或整个 Generated object。

在线调用需要配置 `RVPROBE_LLM_API_KEY` 和 `RVPROBE_LLM_BASE_URL`，
旧 `OPENAI_*` 变量仅为别名，不要求 OpenAI 提供商。检索、离线生成和编译都不需要这些凭据。
JasperGold 和 VCS / URG 分别需要本机工具、许可证及 bench。

## RAG 对照

```sh
python3 experiments/alu_rag_ablation.py generate --out out/experiments/alu-runtime-ablation --env-file /path/to/provider.env
python3 experiments/alu_rag_ablation.py solve --out out/experiments/alu-runtime-ablation
python3 experiments/alu_rag_ablation.py replay --out out/experiments/alu-runtime-ablation
python3 experiments/alu_rag_ablation.py summarize --out out/experiments/alu-runtime-ablation
```

默认两条历史残余任务、每条每组 5 次、共 20 次调用；temperature 0.3，每个样本一次。
它们是固定任务，不是每个 round-1 新样本的自适应 round-2。
RAG 版本 4 只含框架资料和参数化写法；两组只改变检索上下文。
每个样本有自己的 source 目录，串行求解用于共享 Mill 缓存与 EDA 资源，不再因为共享源码槽位。
可在另一终端运行一个 `solve --follow`，完成后再 replay。

生成 manifest 记录 `runtime-ut-v1`、RTL / IO / generator / prompt / corpus 哈希并保存快照。
求解及回放拒绝旧契约；summarize 可审计旧结果，但明确保留 `legacy-static-ut` 分类。
回放会核对 bench RTL 哈希，使用同一次 VCS build、seed 1 和每轮相同的 baseline。
失败和缺失样本保留分母；coverage 均值只按实际测量样本统计，不能把未测量当作 0。

注意：当前 bench driver 按 transaction 等待 done，不是逐周期 driver。
动态 temporal witness 的周期关系、独立 witness 的 reset 初态不由 sequence 导出自动保证。
因此仍需检查回放；不能把所有 generated 样本直接算作复现了意图。
`proof_only_samples` 也不是已完成证明的数量。现有 scoreboard 只计数，不校验算术结果。

## 复现边界与历史结果

本仓库包含原始 ALU RTL、IO manifest、框架和脚本。
完整 HAVEN bench、两个 URG 残余、历史 round-1 sequence 位于旧主机的 `out/`，没有纳入 Git；
上述消融还需通过 `--tb`、`--previous-sequence` 提供这些输入。只 clone 不足以重放历史覆盖分。

2026-09-04 的 95.00 / 177-of-179 是旧静态 UT 流程结果，不是当前动态 UT 的评测。
旧污染 RAG 的在线 / 离线收益结论已经撤回；纠正后的在线对照尚未执行。
原始审计文件保持不变。

历史两行不可达属性保存在 `alu_deadcode_formal.sv` / `prove_deadcode.tcl`，不进入 RAG：

```sh
experiments/haven_tb/eda-shell -c \
  'cd /path/to/zaozi && jg -batch -tcl experiments/haven_tb/alu/prove_deadcode.tcl -proj out/alu-deadcode-jg'
```

历史预期状态为 `line_336_fp_counter_default unreachable` 和
`line_401_f2i_shift_ge_32 unreachable`；本次结构重构没有重新执行这两个证明。
