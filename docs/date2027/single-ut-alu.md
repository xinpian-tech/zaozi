# ALU：每轮一个 UT、多个 Gen、无模型环境假设

日期：2026-09-06。在线实验已完成，停止原因 `no_line_progress`，没有声称覆盖全部闭合。

## 结果

| 阶段 | UT 数 | Gen 数 | 新覆盖行 | 累计行覆盖 | 本轮模型 token |
|---|---:|---:|---:|---:|---:|
| 复位 / 空闲初始化 | 0 | 0 | — | 41/179（22.91%） | 0 |
| 第 1 轮 | 1 | 27 | 134 | 175/179（97.77%） | 64,288 |
| 第 2 轮 | 1 | 3 | 2 | 177/179（98.88%） | 43,709 |
| 第 3 轮 | 1 | 2 | 0 | 177/179（98.88%） | 52,706 |

三轮均首次编译 / 求解 / 回放通过，无修正请求，无人工修改模型源码。合计 3 次模型请求、
3 份模型 UT、32 条 witness、服务报告 160,703 token，自动闭环耗时 1,465.74 秒（约 24 分 26 秒）。
最终累计回放包含 183 个正式 witness 周期、762 个采样周期、549 次输出端口检查，全部通过。
采样周期还包括初始化、各 witness 的独立复位及排空周期，不全是 formal witness。

| 指标 | 初始化 | 最终累计回放 |
|---|---:|---:|
| line | 41/179（22.91%） | 177/179（98.88%） |
| cond | 41/212（19.34%） | 184/212（86.79%） |
| toggle | 3/1916（0.16%） | 1732/1916（90.40%） |
| branch | 10/65（15.38%） | 62/65（95.38%） |
| 四项等权均分 | 14.45 | 92.86 |

第 2 轮补上 208、209 行；第 3 轮各项覆盖均无增加。剩余 336、401 行仍计入分母，
第一轮生成的两项疑似不可达说明保留为 pending，本次没有执行独立不可达证明。
第 3 轮的两个目标分别描述 AND 请求及 FP2INT 小数转零；名称不能证明它们命中了对应残余分支。

与前次多 UT 实验描述性比较：最终行覆盖同为 177/179，综合覆盖分由 85.73 变为 92.86，
模型请求由 4 次变为 3 次，token 由 236,723 变为 160,703。但 UT 组织、场景限制、目标数和
模型输出同时变化，不能把差异单独归因于禁止 Assume、单 UT 或 RAG。

## 固定流程

生成契约为 `runtime-ut-v4`，RAG v10。每次模型回答只有一个 `ut` 对象，包含
`module`、`generationLabels`、完整 `source`，另有待独立验证的 `proofObligations`。
一个 UT 可以包含多个 Gen；不再要求一场景一 UT，也不要求每轮固定数量的目标。

模型不得新增 Assume / restrict，包括复位 Assume。场景操作数、请求条件和跨拍保持关系全部
写在对应 Gen 中。模型源码逐字节保存，不由人工补充、修改或转换。响应检查拒绝 Assume 和多个
@generator；编译后的 UT SV 再检查没有 assume / restrict，所有断言与声明的目标标签完全一致。
这些校验是实验契约检查，不是执行不可信 Scala 的安全沙箱。

固定 runner 对 UT lower 一次，再逐目标移除其他 Gen 断言进行求解；不把目标合取，不把其他目标
作为环境假设。原始设计、IO 连线、监测状态和环境保持一致。JG 原有 `clock clock` / `reset reset`
负责初始化及复位后的搜索；回放采用固定复位前导。没有为 ALU 新增特殊的求解约束或 DUT 实现。

一次 UT 生成可以产生多条独立 witness，这不代表多个 UT。每条 witness 独立复位后回放，仍按
`cycle-replay-v1`、`jg-full-design-v1` 检查输入、时序和可见输出的已知位，再测量累计覆盖。

## 实验设置与产物

- 模型：`deepseek-v4-flash-vision-exp`，temperature 0.3，单次请求超时 600 秒。
- 预算：最多 3 轮、每轮最多 3 次编译/求解反馈修正；连续 1 轮没有新增覆盖行即停止。
- 每个 Gen 的 JG 时间预算：120 秒。
- 初始化：2 拍复位、1 拍空闲，不触发 start，没有随机前置输入；初始行覆盖 41/179。
- 原始 RTL、IO manifest、复位/排空配置沿用前次完整 UT 实验；运行期间框架代码和 RAG 固定。
- 在线目录：`out/experiments/alu-single-ut-live-20260906T111935Z`。
- 控制与审计目录：`out/experiments/single-ut-20260906T111935Z-control`。

`live-provenance.json` 记录运行前后源码哈希；`audit_live.py` 检查回答原文、唯一 UT、目标标签、
编译后假设、witness 哈希、固定时钟复位设置和回放结果。模型失败回答也保留，不人工修补。
本次审计通过：运行前后全部被记录的框架 / RAG / RTL 哈希一致；每轮仅一个模型源文件和一个
共享 lowered SV；全部 32 个目标引用对应轮的相同源码哈希；3 份编译后 UT 的假设数均为 0。

原始回答与可读源码：

- 第 1 轮：[ModelUT.scala](../../out/experiments/alu-single-ut-live-20260906T111935Z/round-1/generation/attempt-1/sources/ModelUT.scala)。
- 第 2 轮：[ModelUT.scala](../../out/experiments/alu-single-ut-live-20260906T111935Z/round-2/generation/attempt-1/sources/ModelUT.scala)。
- 第 3 轮：[ModelUT.scala](../../out/experiments/alu-single-ut-live-20260906T111935Z/round-3/generation/attempt-1/sources/ModelUT.scala)。
- [自动汇总](../../out/experiments/alu-single-ut-live-20260906T111935Z/summary.json)与[审计](../../out/experiments/single-ut-20260906T111935Z-control/live-audit.json)。

上述 `out/` 产物属于本机实验目录，不是入库文件；迁移环境时需一并保存完整运行目录。

## 已观察到的目标语义边界

第一轮返回一个 UT、27 个 Gen，全部求解和回放通过，覆盖达到 175/179；剩余行 208、209、336、401。
其中 `fp_add_inf_inf_nan` 虽然名为无穷大相加，其 witness 在第 0 拍已用另一组输入发起 FP 运算，
第 1 拍才出现目标要求的两路无穷大输入。由于 FP 单元此时忙碌，这次请求没有被接收。
模型的目标只要求请求条件与 5 拍后结果共同出现，没有排除更早的在途事务，所以仍可满足。
实测覆盖没有把 208、209 行算作命中，自动将它们送入下一轮；没有人工补写 Gen 或环境约束。

第二轮改用输入型目标。在独立复位后的单拍 witness 中发起请求，再由固定的 16 拍排空执行后续
流水级，最终覆盖 208、209。因此新增覆盖包含 witness 之后的运行，不应声称模型已用完整时序
目标约束并检查了这笔事务的全过程。排空仍属于实际仿真覆盖，但不属于 formal witness 输出检查。

这说明应依赖实际回放覆盖，而不能依据 Gen 标签或末端输出判断某个操作确实被接收、某条分支已命中。
解决此类场景关联问题需要模型在 Gen 中写出必要的空闲 / 请求 / 完成时序，不应补成全局 Assume。

## 复现命令

在仓库根目录执行，使用新的输出目录，需要 Nix、CIRCT、JG、VCS、URG 和许可证：

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 \
  --model deepseek-v4-flash-vision-exp --temperature 0.3 \
  --timeout 600 --jg-time-limit 120s --rag local \
  --env-file /path/to/provider.env \
  --out out/experiments/my-single-ut-alu
```

离线验证可用本次各轮成功回答的 `response.txt`，按轮次重复传入 `--response-file`，不传
`--env-file`。这会重新编译、求解、回放，不重新调用模型，也不重现在线的失败修正过程。

```sh
python3 experiments/coverage_flow.py \
  --replay-config experiments/designs/alu_replay.json \
  --rounds 3 --attempts 3 --patience 1 --rag local \
  --response-file out/experiments/alu-single-ut-live-20260906T111935Z/round-1/generation/attempt-1/response.txt \
  --response-file out/experiments/alu-single-ut-live-20260906T111935Z/round-2/generation/attempt-1/response.txt \
  --response-file out/experiments/alu-single-ut-live-20260906T111935Z/round-3/generation/attempt-1/response.txt \
  --out out/experiments/my-single-ut-offline
```

保存回答固定模型输出，不保证不同工具环境或求解器运行产生完全相同的 witness。

## 框架回归

普通 Python 回归 76 项中 66 项通过，10 项商业工具 / 编译测试默认跳过后单独执行。
CIRCT / scalac / JG 测试 25/25 通过；真实周期回放测试 16/16 通过；后端 Scala 测试 7/7，
RAG 示例测试 6/6 通过。Python 分组包含重复的普通测试，不能直接相加。
日志与命令见控制目录中的 `VALIDATION.md`。检查覆盖单 UT 多目标、互斥目标隔离、历史有效性不串扰、
禁止环境假设、原始源码哈希以及故意改错非目标输出位后回放失败等边界。

## 证据边界

本次是单次流程验证，不是受控的 RAG A/B 或多样本效果评估。与
[前一次多 UT 实验](full-ut-alu.md) 的覆盖率和成本只能描述性比较，不能归因于单独某项修改。
Gen 标签不证明目标覆盖项已命中，pending proof 不排除残余，回放通过不等于算术功能正确性证明。
