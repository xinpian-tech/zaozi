# IV.A Experimental Setup：基于现有实验的可核实草稿

适用范围：四方法原主实验及 2026-09-16 RVProbe 修复＋统一大预算重跑。增量对话试跑、Direct SV Constraint 和反馈消融不能当作已完成主实验写入。本文件不修改运行中的实验。

## 1. 测试集与比较边界

我们使用本地 HAVEN 测试集中可用的 16 个开源硬件设计，覆盖计算单元、密码核、存储器及外设控制器，包含直接 IO、Wishbone 和 AXI4-Lite 三类主接口。具体设计如下。这里的协议分类指主控制接口，不排除 UART、SPI、I2C、MII、SDRAM 等附加 IO。

| 主接口 | 设计 |
|---|---|
| Direct IO（3） | ALU、AES、SHA3 |
| Wishbone（8） | UART、CAN、ETHMAC、I2C、GPIO、SIMPLE_SPI、SPI、SDRAM |
| AXI4-Lite（5） | AXIL_RAM、UE_TIMER、UE_GPIO、UE_SPI、UE_UART |

本实验比较固定验证环境上的 Stage-2 激励生成，而非重新评估完整 UVM testbench 自动合成。每个设计的 RTL、规格、接口映射、时钟与复位、验证组件以及初始激励在 Stage-2 开始前冻结。所有方法从相同初始激励覆盖率出发，Stage-2 不允许重新生成环境、修改 DUT 或修改初始激励。共享环境的构建费用单独保留，不重复计入每个方法的 Stage-2 token 和时间。

**来源披露：**固定环境基于 HAVEN 的组件和序列生成流程搭建，并经过离线适配、修复及仿真验证。ETHMAC、SIMPLE_SPI、SPI、UE_GPIO、UE_SPI、UE_UART 和 SDRAM 的来源记录保留人工诊断／修正标记；ETHMAC 的记录明确包含人工依据 RTL 修正的 9 个基线意图。因此不能声称 16 个 Stage-1 全部由同一 LLM 自动生成且未经修改，也不能把 Stage-1 包装阶段的零模型调用解释成原始环境生成成本为零。本文将其作为固定公共实验基础设施，并公开来源记录与文件哈希。

## 2. 从 HAVEN 迁移到共享评估框架

迁移保留 HAVEN 的测试设计和规格、UVM 组件组织、协议事务 DSL、代码生成与仿真/覆盖率工具链，并将环境准备与激励生成解耦。

1. **冻结 Stage-1。**收集编译和基线仿真已验证的组件、初始 DSL 与实际 SV 序列，建立固定环境包。每次运行核验 RTL、规格、组件、序列与 HAVEN 实现的 SHA-256，禁止在 Stage-2 中改变这些实验输入。基线不保证全部为随机激励，也不是“什么也不跑”：它是实际执行既有初始序列的测量。
2. **从 RTL 提取接口。**使用 CIRCT 的 `circt-verilog --ir-hw --top=...` 展开 RTL，提取真实方向和位宽；结合展开结果与固定配置识别时钟、复位及附加复位。不能将内部状态条件误认为可操纵的外部复位。需要时用薄封装显式处理双向端口，保留原 DUT 实现，覆盖率仍针对原 DUT。
3. **适配并固定验证组件。**迁移期间修正端口/事务字段宽度、地址映射、AXI-Lite 通道字段、Wishbone ACK/ERR/RTY 终止、多时钟和双向 IO 等接口问题；处理共享 item 中不应限制后续激励的场景约束。此类修复在冻结环境之前完成，不能只为某个方法修改组件或降低错误判定标准。
4. **保留方法特有接口。**HAVEN Stage-2 保留原生 DSL 到事务 driver/BFM 的通路。RVProbe 和两个 direct 前端使用 `independent-dut-v1` 边界：允许生成器决定可驱动数据输入的事件序列，不受 HAVEN 事务模板的固定时序限制；clock/reset/static 等固定环境信号不可由模型覆写。比较的是同一 DUT、固定环境边界与覆盖标准，不是强迫所有方法共用相同的事务 API。
5. **统一独立序列仿真。**包括基线在内，每条序列从独立的新仿真进程开始，避免前一条序列遗留状态影响下一条序列。后续轮次复用经哈希验证的已完成序列测量，使用 URG 合并基线与已接受新增序列的覆盖数据库，不要求每轮重复执行所有旧序列。
6. **接入形式激励后端。**RVProbe 的 LTL 经类型检查和 CIRCT lowering 后交由 JasperGold 求解；Direct SVA 直接提供性质表达式，并复用 witness 采样、候选处理和原生回放验收。将求得的输入轨迹回放到原 RTL，检查输入事件及原始 Cover 命中，失败候选不计入覆盖率。形式后端不是在 zaozi stdlib 中重写 DUT。
7. **统一记录与失败处理。**覆盖率、请求 usage、求解/仿真事件、错误及来源哈希均持久化；单个设计失败不阻止剩余设计运行。已完成任务经校验归档后移出内存盘。

补充说明：共享协议组件的早期修复不意味着 HAVEN 原始论文使用了同样的修复或执行策略。本实验是 HAVEN 衍生的 Stage-2 评估环境，不是对其原论文数字的原样复现。

## 3. 已运行的对照方法

| 方法 | 模型输出 | 执行和验收 |
|---|---|---|
| Direct Stimulus | JSON 输入赋值及保持周期，具体数值和调度由模型给出 | 固定 raw IO 回放；无形式求解器，不声称自然语言意图已满足 |
| Direct SVA | IO 上的命名 SVA property 表达式 | 固定 wrapper、JG 求解、witness 采样、原 RTL 四态 Cover 验收 |
| HAVEN Stage-2 | 原生协议/事务 JSON DSL | 固定 HAVEN codegen、driver/BFM 与仿真 |
| RVProbe | 局部 typed LTL 表达式与 `Gen` 调用 | 固定 UT 包装、类型检查、lowering、JG、采样和原生回放验收 |

两个 direct 前端是本实验构建的基线，不应标为 CorrectBench 等外部工具的原样复现。Direct SV Constraint 尚未完成，当前 Setup 不能将其写成已有第五种结果。Direct SVA 是前端表示的对照，不是仅改变类型系统一个因素的消融。

## 4. 输入上下文与闭环

RVProbe 和两个 direct 方法初始得到 DUT spec、IO、固定环境及各自表示的说明，通过只读工具按需读取 RTL 和框架参考；不把 DUT 历史答案加入框架 skill。后续覆盖轮提供测得的覆盖缺口及本次运行中已接受的历史。HAVEN 保留原生 full-inline/DSL 上下文，不声称所有方法的 prompt 内容或长度相同。

原主实验及完整大预算 RVProbe 批次使用独立证据请求策略：累积、去重证据后重新构造请求，后续请求不继承模型推理。新增增量工具对话路径仍属单独的三设计试跑，不应反向描述成已有 16 设计结果采用的实现。

闭环依次执行：测量基线、基于当前反馈生成候选、执行对应验证与仿真、接受有效新增激励、更新累计覆盖率。无有效新增、模型主动停止或错误预算耗尽也会终止。停止条件在仿真完成后判断，不能把形式 cover 可达直接当作仿真代码覆盖率。

## 5. 实际参数

| 参数 | 原主实验 | RVProbe 修复＋大预算重跑 |
|---|---|---|
| 请求模型名 | `deepseek-v4-flash-vision-exp` | 相同 |
| Temperature | 0.3 | 0.3 |
| 输出预算 | 主实验未显式统一指定 max_tokens，依赖服务端默认 | 每请求 393,216 tokens，包含推理与最终输出 |
| 思考强度 | 主实验不得统一追认成 max，按请求记录说明 | 显式 `max` |
| 单 HTTP 请求墙钟上限 | 600 秒 | 3,600 秒 |
| 最大覆盖反馈轮数 | 3 | 3 |
| 目标综合覆盖率 | 100% | 100% |
| 停滞阈值 | 相邻有效测量增益 < 0.1 个百分点即停止 | 相同 |
| 每轮意图/DSL 条目上限 | 4；HAVEN 的 DSL 条目不等于 4 条 witness | 相同 RVProbe 限制 |
| 每 intent 的 sequence | RVProbe/SVA 最多 4 条有效 witness；Direct Stimulus 显式 1–4 条 | RVProbe 最多 4 条 |
| 源码/格式修复 | 最多 3 次候选尝试，包含初次生成 | 相同 |
| 运行期修复 | 每覆盖轮最多额外 1 次 | 相同 |
| 检索对话预算 | direct/RVProbe 每对话最多 24 次模型请求、64 个工具条目 | 相同；批量查询逐项计数 |
| JG 初始求解时限 | 每目标 120 秒 | 相同 |
| witness 再采样时限 | 每次采样求解 30 秒 | 相同 |
| 辅助候选求解 | 启用时每次 120 秒；仍需原生验收 | 同上，Yosys 0.67 路径可用 |
| 仿真/采样随机种子 | 20260906 | 相同；不是 LLM seed |
| 主要时钟周期 | 10 ns | 相同 |
| 复位前缀 | 10 个主时钟周期，极性由设计固定 | 相同 |
| 附加时钟 | CAN/SDRAM：6 ns；ETHMAC RX/TX：40 ns | 相同 |
| EDA 仿真超时配置 | 300 秒，另有具体 UVM/driver 的轮询限制 | 相同 |
| 调度 | 主批次最多 3 个设计任务并发；部分 HTTP 402 续跑最多 2 并发 | 3 个设计并发 |

注：不能把“3 覆盖轮”理解成最多 3 次模型请求；检索、局部源码修复和运行期修复可能在一轮内触发多次请求。明确瞬态 HTTP 错误最多 3 次有条件尝试，截断/未知投递不自动重发。Direct Stimulus 另有限制：每条 sequence 最多 4,096 个 step、10,000 个主时钟周期。

新增初始化处理：支持范围内用原 RTL 仿真后的复位状态初始化 JG，保持原 reset 序列；未知位不补零。它是最新修复的一部分，不能声称所有历史批次均采用了该策略，亦非通用形式/四态仿真等价证明。

## 6. 覆盖率与成本指标

覆盖率使用 VCS/URG 对目标 DUT 收集 line、condition、toggle、branch、FSM；只纳入该设计分母非零的指标。设设计 d 的有效指标集合为 M_d，各指标总 bin 和命中 bin 为 N_dm、H_dm：

`C_d = (1 / |M_d|) × Σ_m (100 × H_dm / N_dm)`。

跨设计结果为 16 个 `C_d` 的等权平均，不是合并不同设计的 bins。适配 wrapper 不应替代原 DUT 成为计分对象；每方法基线及覆盖率分母经一致性检查。当前没有统一功能 covergroup 或独立的自然语言意图正确性评测，因此这些指标不能标为 Functional Coverage 或 intent pass rate。

每设计使用最后一次接受的有效累计覆盖率。若没有任何有效新增激励，则使用 Stage-1 baseline；后续失败不抹去已有结果，失败也不被删除或记作零覆盖。全部失败的 token、请求及运行时间均保留。

token 分为缓存命中输入、未命中输入及输出；推理 token 是输出的子集，不重复相加。未返回 usage 的调用记为未知，相应合计为已报告小计。实际 USD 需按服务商费率和时段计算，目前费率未确认，不用总 token 比例冒充费用比例。Stage-2 时间、基线/准备时间及并发批次墙钟分列；phase 时间可能嵌套，不能直接相加。记录覆盖轮次与 HTTP 请求次数两个不同指标。

## 7. 软件、硬件与可复现性

日志确认仿真器为 Synopsys VCS V-2023.12（UVM 1.2），形式工具为 JasperGold 2021.03p002；CIRCT/Scala 与开源依赖由 Nix flake 锁定，商业 EDA 经 FHS 包装运行。RVProbe 编译执行使用 bubblewrap 隔离，模型只具有允许的只读工具访问能力。

当前主机核对为 AMD Ryzen 9 7940HS（8 核/16 线程）、约 30 GiB 可见 RAM，NixOS 25.11。此为当前主机信息；若论文声称每个历史批次均在相同硬件/资源条件下运行，应补齐对应批次的资源快照，而不是将当前读数冒充历史记录。实验使用本机 EDA 和远端模型服务；/dev/shm 暂存，完成后校验归档到持久存储。同机并发和模型服务负载会影响墙钟时间。

保留 manifest、固定环境文件哈希、提示词、模型最终输出、工具证据、用量、求解日志、回放结果及覆盖数据库。原主批次、大预算重跑和交互优化试跑分别报告，不按每设计最高覆盖率拼接成单一同配置实验。

## 8. 本地证据索引

- 设计列表：`experiments/design_inventory.py`
- 固定环境来源：`/var/storage/workspaces/clo91eaf/fixed16-environment-20260912-v3/{manifest.json,stage1-map.json,<design>/stage1-costs.json}`
- 环境冻结和哈希：`experiments/frozen_stage1.py`
- RTL 端口/环境：`experiments/rtl_environment.py`、`environment_preflight.py`、`environment_policy.py`
- 方法分流与闭环：`experiments/coverage_flow.py`、`directed_baselines.py`、`directed_experiment.py`
- 独立仿真与统计：`experiments/isolated_replay.py`、`haven_shared.py`
- 原主实验协议：`docs/date2027/fourway-campaign-20260915.md`
- 最新完整 RVProbe manifest：`/var/storage/workspaces/clo91eaf/rvprobe-fixed16-repaired-max384k-20260916-v1/<design>/flow/paired/manifest.json`
- 实际请求/用量：各轮 `generation/manifest.json`、`dialogue-*.json` 和 `events.jsonl`
- 本文尚未实现或完成的项目不得写成既有 Setup：SV Constraint 对照、反馈消融、统一功能覆盖、实际 USD 账单、全方法统一新预算的重复运行。
