# 论文数据快照：历史配对结果与近期 RVProbe 重跑

采集时间：2026-09-15T13:19:11.456246+00:00。这是可追溯的 preliminary results，不是已完成的最新版统一 16 设计主实验。

## 口径与审计

- 历史表：9 月 12 日配对批次，16 对均结束；HAVEN 8/16 完成，RVProbe 9/16 完成，双方完成 5/16。
- 历史批次跨设计存在 2 个框架指纹（v1/v2，四个环境预检失败后重跑）；保留原报告选取规则与原始引用，未选最高覆盖率。它不是全设计同一代码版本的最终评测。
- 已从每对原始 summary 核对：两侧 DUT 模块范围、有效覆盖指标、bin 分母及原报告数值一致。综合覆盖率是有效指标百分比的等权平均；指标为 line/cond/toggle/branch 及有效时的 FSM，具体列、模块和 bin 见 JSON。
- Stage 1 是固定实验环境。只统计所选 Stage 2 尝试；不含共享基线准备、Stage 1、归档，以及未列入的历史开发/失败重跑成本。并行主机耗时不能视为隔离速度基准。
- tokens 是 provider 返回的 input+output 总量（包含缓存命中 input），不是金额。轮次是覆盖闭环迭代记录数，请求数是实际模型请求数，两者不能混用。
- 请求模型固定为 deepseek-v4-flash-vision-exp，provider 报告名为 deepseek-flash；没有独立验证 provider 内部版本。
- S=completed；F=failed；F₀=失败且零验收轮，覆盖率仅为基线。其余失败覆盖率也仅是最后验收状态，不是完整成功结果。

## 表 1：历史 16 设计配对（每格 HAVEN / RVProbe）

| 设计 | 状态 H/R | 综合覆盖率 % H/R | Tokens H/R | 轮次 H/R | 请求 H/R | Stage 2 分钟 H/R |
|---|---|---:|---:|---:|---:|---:|
| alu | S / F | 97.87 / 95.74 | 160,029 / 391,526 | 3 / 1 | 3 / 11 | 7.44 / 10.48 |
| aes | S / S | 95.84 / 95.82 | 133,805 / 283,377 | 2 / 2 | 2 / 10 | 4.24 / 15.70 |
| sha3 | S / S | 99.99 / 99.99 | 54,712 / 315,879 | 2 / 2 | 2 / 14 | 2.62 / 8.75 |
| axil_ram | F₀ / S | 85.80 / 92.94 | 74,242 / 568,791 | 0 / 2 | 3 / 18 | 1.90 / 25.39 |
| ue_timer | F₀ / S | 82.17 / 99.84 | 141,969 / 700,021 | 0 / 3 | 3 / 20 | 4.21 / 25.44 |
| uart | S / F₀ | 95.25 / 90.93 | 177,267 / 254,827 | 3 / 0 | 3 / 7 | 7.30 / 14.98 |
| can | F₀ / F | 95.91 / 97.78 | 358,602 / 3,339,410 | 0 / 1 | 2 / 48 | 9.30 / 26.32 |
| ethmac | F / F | 91.93 / 92.94 | 1,229,855 / 5,671,472 | 2 / 1 | 5 / 73 | 14.81 / 34.08 |
| i2c | S / S | 88.92 / 90.43 | 109,207 / 884,844 | 2 / 2 | 2 / 22 | 3.50 / 12.73 |
| gpio | S / S | 93.08 / 97.90 | 182,562 / 1,169,550 | 3 / 3 | 3 / 36 | 5.83 / 21.00 |
| simple_spi | S / S | 91.67 / 93.17 | 292,914 / 785,153 | 3 / 3 | 5 / 21 | 12.07 / 39.54 |
| spi | S / F | 97.69 / 95.77 | 205,266 / 763,598 | 3 / 2 | 4 / 22 | 8.76 / 18.11 |
| ue_gpio | F₀ / S | 54.26 / 97.62 | 175,108 / 1,736,191 | 0 / 3 | 3 / 37 | 3.04 / 26.28 |
| ue_spi | F₀ / F | 46.04 / 85.80 | 186,838 / 776,752 | 0 / 1 | 3 / 19 | 2.72 / 12.21 |
| ue_uart | F₀ / F | 34.86 / 85.14 | 139,003 / 529,530 | 0 / 1 | 3 / 16 | 1.74 / 8.60 |
| sdram | F₀ / S | 72.27 / 85.82 | 198,150 / 3,474,945 | 0 / 3 | 2 / 61 | 3.64 / 81.19 |

## 可以写出的观察

- 双方完成的 5 个设计（aes、sha3、i2c、gpio、simple_spi）中，平均综合覆盖率为 HAVEN 93.90%、RVProbe 95.46%，差 1.56 个百分点。仅对该成功子集成立，不能外推到全部 16 个设计。
- 该子集总 tokens 为 773,200 / 3,438,803，RVProbe 为 4.45 倍；累计侧耗时 28.25 / 97.71 分钟，比例 3.46。因此现有数据不支持“RVProbe 更省 token/更快”。
- 全部 16 个所选尝试（含失败）的 tokens 为 3,819,529 / 21,645,866；累计侧耗时 93.11 / 380.79 分钟。失败会提前结束，不能将此比例当作等工作量性能比较。
- 包含失败/基线的最终保留覆盖率均值为 82.72% / 93.60%。这不是双方成功运行的覆盖均值，不能把差值全归因于激励质量。
- 当前缺少统一最新版全量重跑、多随机种子方差，以及 HAVEN 激励是否满足意图的独立验收；不据此宣称统计显著性、普遍鲁棒性优势或理论正确性已经得到实验证明。

可用于英文初稿的保守表述：

> In a preliminary paired evaluation, HAVEN and RVProbe completed 8 and 9 of the 16 design runs, respectively. On the five designs completed by both tools, the mean composite coverage was 93.90% for HAVEN and 95.46% for RVProbe. This conditional coverage improvement came with 4.45 times the reported token usage. Failed runs are retained and reported separately; these results do not establish a general efficiency or robustness advantage. The campaign includes two framework revisions and has not been evaluated across multiple seeds.

## 表 2：近期 RVProbe 重跑快照（不能直接替换表 1）

这里只列近期选定重跑中的 8 个设计，来自 3 个批次；7 个完成，ETHMAC 失败。其余设计的近期版本结果不在此表范围。数值变化混合了模型重新生成、框架与提示修改，不能作为受控消融结论。

| 设计 | 状态 | 综合覆盖率 % | Tokens | 轮次 | 请求 | Stage 2 分钟 | 批次 |
|---|---|---:|---:|---:|---:|---:|---|
| aes | S | 95.83 | 201,294 | 2 | 8 | 9.59 | rvprobe-full16-stable-20260914-v1 |
| sha3 | S | 99.99 | 128,181 | 2 | 7 | 5.35 | rvprobe-full16-stable-20260914-v1 |
| axil_ram | S | 92.94 | 75,219 | 2 | 4 | 8.34 | rvprobe-full16-stable-20260914-v1 |
| ue_timer | S | 99.84 | 155,719 | 3 | 6 | 12.54 | rvprobe-full16-stable-20260914-v1 |
| alu | S | 97.07 | 175,194 | 3 | 6 | 14.70 | rvprobe-retry4-20260915-v1 |
| uart | S | 97.30 | 251,341 | 3 | 7 | 39.44 | rvprobe-retry4-20260915-v1 |
| can | S | 97.64 | 1,324,149 | 3 | 20 | 38.90 | rvprobe-retry2-20260915-v2 |
| ethmac | F | 94.65 | 3,114,764 | 2 | 34 | 34.45 | rvprobe-retry2-20260915-v2 |

ETHMAC 第三轮在辅助编码阶段失败：`encoded goal rail reintroduces an unencoded X/Z value`。其 94.65% 为前两轮验收结果，但 tokens 含第三轮失败成本。CAN 已完成并归档，当前这批不再运行。

最新框架的常量诊断修复已通过保存回答的离线复核；本次 ETHMAC 三轮未触发常量错误，不能把它当成模型自动修正成功的直接证据。

## 文件与追溯

- 同名 JSON 包含两表数值、完整来源、summary SHA-256、框架指纹、配置、有效指标、bin、分项 tokens、请求数和失败原因。
- 同名 CSV 为长表，section 区分 historical_paired 与 recent_rvprobe；LaTeX 文件含两张表及必要说明。
- 原始历史聚合（包含已列入旧尝试）：/var/storage/workspaces/clo91eaf/rvprobe-paired16-parallel-20260912-v1/report-combined.json
- 本快照不修改实验原记录，不新增模型请求，不补写缺失用量。
