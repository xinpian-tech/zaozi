# 设计 11–16 的人工 LTL 框架验收（2026-09-10）

这是 Codex 代替模型提供 UT 的框架诊断，不是 HAVEN/DeepSeek 正式配对实验。
远程 LLM 请求数为 0；作者 token 用量无法测量，记录 null，不伪造为零。
原 RTL 未改，未向 skill/RAG 加入设计答案或历史 UT；只机械同步了多 include 目录的 API 签名。

## 当前结果

六个设计均完成两轮闭环，合计 48 条新增 sequence 全部在真实四态 IO 上命中原始 LTL。
SDRAM 最后一次成功运行于 2026-09-10 11:10:12 UTC（UTC+8 19:10:12）结束。

| 设计 | 完成轮数 | 新增且通过原始 LTL 的 sequence | 最终行覆盖 | 最终翻转覆盖 | 本次成功运行墙钟秒数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| simple_spi | 2 | 8 | 61.33% | 36.67% | 680.6 |
| spi | 2 | 8 | 63.10% | 45.04% | 390.6 |
| ue_gpio | 2 | 8 | 87.67% | 46.80% | 416.4 |
| ue_spi | 2 | 8 | 78.03% | 43.57% | 512.4 |
| ue_uart | 2 | 8 | 76.88% | 43.58% | 490.4 |
| sdram | 2 | 8 | N/A | 40.80% | 549.3 |

每轮一个 UT、一个人工验证意图，最多采样 4 条；表中不含每个设计的 1 条人工基线。
时间包含 mailbox 等待、人工编写和运行，不是模型推理耗时，也不包含此前失败重启的总成本。
覆盖范围为原始模块；这些少量定向意图仅验收流程，不代表全设计功能验证或覆盖收敛。
SDRAM 的原始顶层 `sdrc_top` 没有可计数 line bin，不能把 N/A 写成 0% 或 100%；
condition/branch 均为 100%，toggle 从基线 117/696 增至 284/696。
机器可读汇总：`out/experiments/manual-six-native-validation-summary.json`，逐条核对了最终 batches 的 LTL 命中记录。

## 执行路径与边界

`manual_stage1.py` 从显式 task、协议映射和诊断 DSL 生成原生 HAVEN 组件、执行真实 VCS 编译；
`manual_flow.py` 冻结实现，用带 request SHA-256 的 mailbox 提供人工回答。
每次通过 `read_skill` 调用取到框架语法，再提交完整单 UT。
后续正常执行源码校验、Scala/CIRCT lowering、JG、固定种子采样、逐条新进程回放、
原始 Cover 在真实四态 IO 上命中检查、URG 覆盖合并和下一轮反馈。
手工基线有 `manual-diagnostic.json` 标记，禁止进入正式模型对照。

成功的前五个目录均位于 `out/experiments/manual-six-<design>-flow-v1`。
SDRAM 为 `out/experiments/manual-six-sdram-flow-v9`，两轮分别是复位锚定完整读写和初始化，各 4 条。
原始回复、skill 调用、所有失败尝试和实际 witness 都保留在各自目录；不改写旧成绩。

## 本轮框架修复

- IO/环境：混合内部同步清零条件不再被误认成顶层复位；静态 SV 数字字面量规范化为有位宽检查的整数。
  共享组件只驱动自己拥有的引脚；AXI 额外输入允许真实 BFM 所有权，不相信伪造所有权标记。
- 多目录 RTL：JG lower、prepared job、TrustedSolver、采样器均改为 `includeDirs` 数组。
  旧单目录 job 需重新 prepare。非 UTF-8 的原 RTL 字节保持原样；只对诊断/上下文做显式解码。
- inout：新增 CIRCT IO 导入后的纯接线包装，保留单个原始 DUT 实例和逐位 Z/冲突解析。
  数据、使能、resolved sense 由真实 BFM/电气网络处理；不 force 外设响应，不复刻 DUT。
  原模块覆盖率和原始 RTL 哈希保持可检查。尚不支持包装的参数覆盖，显式拒绝。
- 原生 SPI BFM：修复多片选位宽、全部 CPOL/CPHA 模式、完整字节及连续传输/复位。
- 原生 SDRAM BFM：修复命令端口漏连和具名次时钟连接、CAS 输出阶段、连续读写、DQM、
  mode-register burst 长度及不同行/列地址别名。保留外部存储器未写位置为零的显式策略。
  这是功能型模型，不是完整 JEDEC 时序检查器；不支持的模式明确报错。
- Wishbone/轮询：经典单拍驱动初始化 CTI/BTE，实际驱动非 BFM 的额外输入；ACK 超时可显式配置。
  读回 poll 只接受已知 true，X/持续不匹配必须失败，最后一次恰好成功不能被误判超时。
- 采样：每次最多 64 个全 trace 范围内的软偏好，固定种子、固定原 witness 长度、不改属性。
  版本 `soft-input-resample-v2` 和上限进入指纹，避免长初始化 trace 优化规模失控。

SDRAM CAS 时点对照了 [Micron 原始模型的公开副本](https://github.com/freecores/mem_ctrl/blob/master/bench/verilog/sdram_models/4Mx16/mt48lc4m16a2.v)：
输出在 CL−1 对应的边沿后开始有效，为 CL 边沿采样准备；本功能模型取零 tAC，不额外插入整拍寄存。

## SDRAM 调试账本（保留失败，不计为成功）

- 纯接线包装：`manual-six-sdram-adapter-v3`；原始模块为 `sdrc_top`，包装为 `sdrc_resolved`。
- 当前共享组件：`manual-six-sdram-setup-v7`，输入 `manual-six-inputs/sdram-v4.json`。
  原生基线完整写入、读回已通过；ACK 上限显式设为 1000 个主时钟，覆盖初始化等待。
- flow-v4：逐拍全输入软偏好采样在 580 秒超时；随后由新组件快照替代，旧运行明确结束为失败。
- flow-v5/v7：完整读回暴露了漏连、CTI、CAS 和初始化等待问题。没有把只写入且 ACK 的早期基线视为完整验收。
- flow-v6：人工命令给错 Stage-1 时间戳，入口未运行；不是 RTL/模型错误。
- flow-v8：初始化 4 条通过；读写意图错误要求至少一拍写等待，导致形式工具填满 FIFO。
  回放拒绝原始 LTL。改为 128 拍空闲的候选在 120 秒内 unknown，不冒充不可达或通过。
- flow-v9：有界初始化前缀可能从较晚拍开始匹配，早期未约束事务仍污染 FIFO；原始 LTL 再次拒绝。
  随后通过同一闭环的 runtime-repair 入口提交复位释放锚定的意图：先观察复位历史与释放，
  再保持空闲到初始化完成、完整写握手、保持读请求直到读 ACK，要求读回值等于写入值。
  4 条读写均通过；后续一轮的 4 条初始化检查也通过，最终 status=passed，stop_reason=round_budget。
  没有使用额外 Assume，也未删除原始读回相等检查。该诊断 JG 上限为 300 秒，不能与前五个的 120 秒预算混作同条件性能比较。

采样 v2 的 flow-v8 初始化新增三条求解分别为 3.855、3.677、3.996 秒；旧 v1 超时。
这是本地诊断开销观察，不是模型 token 或覆盖率优势证据。前五个成功流程使用 v1，SDRAM 新尝试使用 v2。

## 回归与复现

- Python：`manual-six-python-tests-v7.log`，240 passed、16 skipped。
- HAVEN 组件：`manual-six-haven-tests-v6.log`，55 passed、1 skipped、5 subtests passed。
- Scala/JG：`manual-six-jg-tests-v1.log`，4 tests passed。
- 真实 VCS：`manual-six-bidirectional-v2`（6 种电气状态）、`manual-six-spi-protocol-v2`（4 种模式）、
  `manual-six-sdram-protocol-v4`（CL2/CL3、BL8、掩码、两拍数据与跨行无别名）、`manual-six-wishbone-protocol-v1`、
  `manual-six-poll-protocol-v2`（最后一次成功、X 拒绝、非匹配拒绝）。
- HAVEN 可移植组件补丁已更新为 38 文件，并通过正向/反向应用检查。

使用 `nix develop -c experiments/haven-python HAVEN_ROOT ...` 执行这些入口；EDA 走 `experiments/eda-shell`。
复现人工诊断必须显式提供诊断配置和 mailbox 回答，不会自动调用 DeepSeek。
磁盘紧张时只清理了本轮已结束运行的编译二进制/中间缓存；源码、filelist、日志、witness、VDB 均保留。
精确清理目标在 `out/experiments/manual-six-*-cache-cleanup.json` 等清单中，二进制可重新编译生成。
