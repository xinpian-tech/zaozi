# ALU：LLM 直接生成完整 UT 的在线实验

日期：2026-09-06。契约：`runtime-ut-v3`；框架 RAG：v9。
本次为一次完整 UT 在线闭环运行，不是保存回答重放，也不是 RAG A/B 对照。
基于 `12e5c80a2a10c7dee01b22209f8b0ba410055d42` 上的本次工作区修改；尚未提交。

## 结果

| 阶段 | 最终采用 UT | 模型请求次数 | 新覆盖行 | 累计行覆盖 |
|---|---:|---:|---:|---:|
| 仅初始化 | 0 | 0 | — | 41/179（22.91%） |
| 第 1 轮 | 8 | 1 | 115 | 156/179（87.15%） |
| 第 2 轮 | 10 | 2 | 21 | 177/179（98.88%） |
| 第 3 轮 | 2 | 1 | 0 | 177/179（98.88%） |

流程状态为 `completed`，停止原因 `no_line_progress`，`coverage_closed=false`。
最后仍未覆盖第 336、401 行，没有据模型文字排除它们。

- 4 次在线请求，共 236,723 个服务报告 token，包含失败尝试；完整闭环耗时约 31 分 55 秒。
- 模型共提交 30 份 UT 源码（含被替换的失败批次），4 个批次均通过编译。
- JG 共生成 27 条 witness，3 个目标为 infeasible；最终采用 20 份 UT，执行 3 次累计回放。
- 最终回放 489 个采样周期，其中 formal witness 126 拍，378 次输出端口检查全部通过。
- 最终四指标：line 98.88%、cond 81.13%、toggle 70.62%、branch 92.31%；等权分数 85.73。

这不能与此前“1024 组随机输入后再补覆盖”的结果直接比较：初始化、模型输出契约与 prompt 均已改变。
本流程以未覆盖行为反馈目标，不承诺把条件、翻转和分支覆盖同时最大化。

## 模型和框架分别做了什么

模型输出的每项包含 `label`、`module`、`generationLabel` 和完整 `source`。
源码包括 imports、@generator object、architecture、ImportedDut 实例、所有 IO 接线、
时钟/复位上下文、Assume 和 Gen 调用。框架没有把旧 expression 回答重新包装成 UT。

框架只提供共享 `DesignBinding.scala`、固定 `Generated.scala` runner 及工具调用。
JSON 解码后的模型源码原样保存为 `ModelUT*.scala`，编译前校验 SHA-256。
30 份源码全部通过原文一致性审计；运行前后框架、prompt 构造器、RAG 来源及原始 RTL 的哈希一致。
没有人工修改在线回答、注入设计答案、替模型修正约束或改写 ALU。stdlib 没有改动。

使用原始 `experiments/fixtures/haven/alu_top.v`。初始化仅 2 拍复位 + 1 拍空闲，
没有随机操作数或 start 请求。随后每份 witness 独立复位，精确按拍回放，并追加配置中的 16 拍排空。
覆盖统计包含初始化和排空；排空不属于 formal witness，不在其上声称满足 UT 的全部假设。
JG 使用完整设计轨迹导出，回放保留输出已知位检查，不采用旧 COI 占位值。

## 失败与证据边界

第 2 轮第一次提交了 10 份 UT，其中 `fp_sub_underflow`、`fp_add_round_overflow`、`fp2int_nan`
在其模型约束下不可满足。它们不是编译错误，也不等于对应 RTL 分支不可达。
框架将真实求解结果与上一份回答反馈给模型，第二次提交的 10 份 UT 全部生成 witness 并通过回放。
失败批次中其余 7 条已生成的 witness 没有混入覆盖统计。

修正并非都保留了最初的目标强度：模型调整了部分输入值，也把部分带输出比较的目标简化成 `Gen(io.done, ...)`。
因此，实测新增覆盖可以确认，但不能把这轮成功解释为验证了最初所有算术输出预期。
框架自身的 VCD 输出比对没有因此关闭或减弱。

第 3 轮两份 UT 的 label 声称针对第 336、401 行，但实际分别约束整数 AND 操作，以及小于 1 的浮点转整数，
Gen 目标均为 `io.done`。它们能编译、求解、回放，但没有命中目标行。
这直接说明“UT 有 witness”不等于“覆盖意图实现”；流程以 URG 实测无新增覆盖而停止。

两条 pending proof 元数据来自第 2 轮，未做独立形式验证。
第 336 行涉及 `fp_active` 与 `fp_counter` 的可达状态关系，不能仅凭模型的“只赋予已处理状态”描述宣布不可达。
本实验既没有证明这两行不可达，也没有证明 ALU 的算术正确性或全状态等价。

## 复现与产物

从仓库根目录执行，输出目录必须是新的：

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 \
  --model deepseek-v4-flash-vision-exp --temperature 0.3 \
  --timeout 600 --jg-time-limit 120s --rag local \
  --env-file /path/to/provider.env \
  --out out/experiments/my-full-ut-alu
```

需要本机 Nix、JG、VCS、URG 及许可证。在线回答不是确定性的；命令复现流程，不保证再次生成相同源码。

本次原始目录：`out/experiments/alu-full-ut-live-20260906T094832Z/`。
其中有逐次 prompt、response、模型原文、编译报告、JG 日志、VCD、stimulus、回放 schedule、VCS/URG 报告及 summary。
控制目录：`out/experiments/full-ut-20260906T094832Z-control/`，保存运行命令、源码哈希、逐文件审计与回归日志。
这些大体积原始产物保留在本机，不在 Git 或 RAG 语料中。

用本机保存的成功回答离线复现 3 轮回放（不再调用模型，不重演第 2 轮失败修正过程）：

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --response-file out/experiments/alu-full-ut-live-20260906T094832Z/round-1/generation/attempt-1/response.txt \
  --response-file out/experiments/alu-full-ut-live-20260906T094832Z/round-2/generation/attempt-2/response.txt \
  --response-file out/experiments/alu-full-ut-live-20260906T094832Z/round-3/generation/attempt-1/response.txt \
  --out out/experiments/my-full-ut-alu-replay
```

没有原始产物时，仓库中保存的 `experiments/tests/fixtures/completion_intent.json` 可用于无模型的完整 UT 冒烟，
但它不是本次在线样本，不能用它替代上述结果。
