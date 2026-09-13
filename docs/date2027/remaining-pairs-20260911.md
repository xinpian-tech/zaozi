# 剩余配对续跑与框架修复（2026-09-11）

正式完成计数与完整指标以 `fixed16-paired-progress.json` 为准。本轮开始时为
11/16；离线验证不增加该计数。Stage-1、DUT、模型生成的 LTL/DSL 均不手改。

## 新的正式运行

工作根：`/dev/shm/rvprobe-independent-paired-20260911-YuflGB`。
归档根：`/var/storage/workspaces/clo91eaf/rvprobe-independent-paired-20260911-YuflGB`。

- `batch-a`：simple_spi、I2C，双方 Stage-2，显式 `independent-dut-v1`。
  simple_spi 的 HAVEN 完成三轮；RVProbe 首轮遇到错误的精确数量要求。
  I2C 的 HAVEN 在第二轮触发次级复位输入被覆盖的问题；RVProbe 单独继续。
- `batch-b`：修正上述共享接口及数量契约后续跑 simple_spi 完整配对。
  首轮复用 batch-a 保存的完整 DeepSeek 回复，须通过原有
  `saved_generation` 的 DUT/IO、共享组件、模型与源文件校验；后续轮次仍调用模型。
  `continued_generation` 保留先前费用，不能把复用回复记成免费生成。

请求模型固定为 `deepseek-v4-flash-vision-exp`；本轮已返回的服务端名称为
`deepseek-flash`，两者分别记录，不能当作已证明底层模型与名称完全相同。
两侧都重新测量公共基线，不拼接旧共享边界与新独立边界的覆盖率。
部分运行并发，墙钟时间包含资源竞争，不是隔离性能基准。

## 数量契约修复

原 README 和 prompt 明确是每个意图 **最多四条不同序列**，不足不重复凑数。
新加的原生筛选却把四当成必须达到的数量，导致一拍输入完全固定、只剩一个
自由位的目标在两个合法解都通过后仍被判失败。

`pool_target` 现在按已提供的去重样本池与请求上限确定验收目标。
每条被接纳的样本仍必须通过原始 Cover；原生失败不能以“数量不足”为理由忽略。
报告保留 `requested_cap`、`available_pool`、`actual`、`sampling_shortfall`。
不增加未经求解的 drain，不延长轨迹凑数量，也不重复同一输入。

零模型调用的 `offline-spi-pool-v1` 重编译、求解并回放未修改的 provider 回复，
一轮通过，四个意图实际数量为 **2、4、4、4**，共 **14 条**；耗时 202.440 秒。
这是诊断，不是完整配对。

## 次级复位输入修复

I2C 保存的 HAVEN 序列显式要求主复位有效、异步复位保持无效。
独立输入接口此前忽略 `rvp_drive_arst_i`，用主复位覆盖它，原生输入检查因此拒绝。

独立边界现在让次级复位跟随已经公开的独立输入字段；历史共享边界保留原行为。
RVProbe 自动生成的复位前缀本来已包含每个次级复位的值，仍按同一序列回放。
没有修改或重建冻结 Stage-1，也没有单独给 HAVEN 更换 DUT/driver 源文件。

`offline-i2c-resets-v2`：原封不动回放 batch-a/HAVEN 第二轮 **4 条**序列，
全部通过，33.136 秒，零模型调用。`v1` 是错误路径导致的启动失败，记录保留。

## 独立编码诊断（尚未接入正式后端）

更新：已提供显式 `--encoded-witness-yosys` 候选搜索开关，默认关闭。
正在运行 CAN 保存回复的完整离线流程；尚未用该开关计入新的正式配对。
该路径仅提供候选，不承担原始 LTL 的证明。原始采样预算和辅助预算分别记录，
辅助路径只在前者不足时启用，每个候选仍经过原始 Cover/输入/时钟/复位检查。
Yosys 已加入 `flake.nix` 的开发环境，使用锁定的 Nixpkgs 版本。

诊断根：`/var/storage/workspaces/clo91eaf/rvprobe-remaining-encoded-20260911-v1`。
本轮诊断器起初沿用旧冻结外设边界；这些结果不能代替已选定的独立 DUT 验证。
现在显式记录源边界、求解边界、回放边界，默认独立 DUT。

- JG 2021 的声明初始化在当前设置下未生效。最小反例在仅声明初值时错误可达；
  显式加载编码初值后，错误目标不可达，正常写入仍可达。
  初始化只作用于辅助编码模型，不给原 DUT 的未知存储填零。
- Yosys 默认 `proc` 的综合展开不能保持 `if(X)` 的仿真行为。
  改用 `proc -ifx -noopt` 后，VCS 最小测试 24 次逐位比较通过；
  默认展开的负对照出现 81 次不确定掩码差异。
- `run-independent-v3`：CAN 原来失败的读回、发送、错误标志三个目标
  各找到一条候选，并全部通过原始 RTL/原始 Cover 回放。
- `run-independent-v7`：ETHMAC 保存的描述符写后读回目标通过原始原生检查。
- `run-independent-v9`：SDRAM 保存的写后读回目标找到 552 拍候选，并通过
  原始 RTL、原始 Cover 和精确输入检查，`zero_delays=false`，零模型调用。
  v6 的 `unreachable` 实际伴随 `WAS006`，不能称为原目标不可达。
  `sdram-conflict-v2.log` 将冲突缩小为一条总线 known-value 假设。
  根因是向量三态驱动与逐位三态驱动没有按重叠位合并。
  转換采用 `tribuf; simplemap t:$tribuf; tribuf -formal`，保留结构性冲突检查；
  这些辅助检查转换为已知为真的输出谓词，不移除原 RTL 中的约束。
  `smoke_encoded_tristate.py` 对 80 组非冲突输入（含浮空总线）通过原生逐位对照；
  Z 在编码里归为 unknown，不能宣称一般四态三态逻辑等价。
  位宽扩展、锁存历史全局 FF 与输出别名 mux 的转换均有单独记录。
  `async2sync`、离散全局时钟与“无同时驱动”的可选候选子集都有适用限制。
  编码模型的不可达 **不能** 作为原始目标不可达的结论。

所有尝试和失败均保留；不得将辅助编码 Cover 的命中直接当作原始 LTL 通过。
最终仍由原生四态 RTL 上的冻结 Cover 和输入/复位/时钟一致性检查决定是否接纳。
没有把设计答案放入 skill、prompt 或 RAG。

## 存储

对终止的旧 batch-j/SPI 先完整归档并逐文件校验，再清理 248 项可重建缓存，
共 1,754,086,967 字节；源码、波形、覆盖率、日志和费用全部保留。
旧 I2C 的归档校验通过，没有可清理缓存（删除 0 字节）。
新运行也只在完成并验证归档后清理编译缓存。不要清理仍在运行的设计。

## 后续正式尝试

- batch-a/I2C：HAVEN 1 个接受轮后因次级复位输入被覆盖失败；RVProbe 完成 2 轮。
  不拼接两侧结果，整个尝试仍为失败。
- batch-b/simple_spi：HAVEN 完成 3 轮；RVProbe 接受首轮 14 条序列后，
  第二轮 provider 响应 `finish_reason=length`，最终内容长度为 0，未进入编译。
  此轮 5 个 HTTP 请求，195,193 tokens（reasoning 84,200），费用完整保留。
- batch-c/I2C：修复公共次级复位接口后的新双方正式配对，**双方均 completed**，
  各接受 2 轮，以 `coverage_stalled` 结束。Stage-1 未重新生成，HAVEN prompt 未改。
  HAVEN 综合覆盖率 89.0416%、114,256 tokens、253.322 秒；
  RVProbe 综合覆盖率 90.4305%、981,093 tokens、950.710 秒。
  正式完整配对累计 **12/16**，剩余 CAN、SDRAM、ETHMAC、simple_spi。

CAN 完整离线接入 v1：输入意图 4 条通过，但发送目标的辅助求解被旧两态
轨迹的 112 拍上限截断。独立编码已证明且原生通过的轨迹为 122 拍。
候选搜索现在先取得自身已证明的轨迹长度，再固定该长度进行多样性采样；
不向旧轨迹附加未求解的拍，也不改变原 LTL。`offline-can-encoded-v2` 正在验证。
SDRAM 和 ETHMAC 也在用完整保存的 provider 回复做零模型调用的端到端验证。
每次辅助调用记录当前 helper/encoder/initialization 的源码哈希，区分运行版本。

SDRAM 负向检查 `sdram-negative-v9`：将 witness 的 `wb_dat_i` 改为 0，
原始 Cover 按预期拒绝；18.172 秒、零模型调用，不把编译失败当作负向验证成功。

ETHMAC 完整离线 v1 的前三个意图各 4 条通过，但辅助读回候选被原始 Cover
拒绝。定位为 `tribuf -formal` 对单驱动总线丢失使能，将关闭时的 Z 错误替换
为数据。现在在多驱动合并前将单驱动转换为保留使能的 value/X mux；
`tristate-regression-v2` 的 80 组原生测试同时覆盖单驱动和重叠驱动，全部通过。
`run-independent-v10` 使用修正后的统一三态路径，ETHMAC 原始读回 Cover
原生通过。完整 v1 在旧 5 拍长度上继续采样失败，需全新 v2 流程验证。
CAN v2 的发送目标通过 4 条辅助候选；其余目标仍在验证。
临时盘不足时暂停的离线父进程及恢复记录见运行根 `offline-resource-control.md`，
这些诊断耗时不用于论文性能比较。

## 13:33 UTC 后续运行

- SDRAM `offline-sdram-encoded-v1` **完整通过**：三意图分别 4、4、4 条，
  写后读回经过 16 个普通候选拒绝后，由辅助路径取得 4 条原生有效序列；
  额外的重复候选不计入序列数量。全程零新模型调用。
- 已启动 `batch-d/sdram` 新正式双方配对，显式开启
  `--encoded-witness-yosys`；同一冻结 Stage-1、同一公共回放环境、HAVEN prompt 不变。
  完成前不计入第 13 个完整配对。
- CAN v2 在人工暂停父进程后触发 300 秒编译墙钟超时，属于本次诊断调度问题，
  不是 DUT/LTL 失败。保留该失败尝试，已启动 `offline-can-encoded-v3` 重跑，
  后续不再暂停正在计时的子进程父进程。
- ETHMAC v1 材料已归档；修复后的 v10 单目标原生验证通过，完整 v2 尚待运行。
- simple_spi 仍缺完整配对，最近正式尝试第二轮 provider 截断；没有静默重试或
  把失败计为完成。复用首轮的费用在导出中有明确增量标记。
- 最新框架单元测试：356 项，340 通过、16 跳过。三态原生回归 v2：80 组通过。

已完成的 I2C 结果和所有列入尝试见 `fixed16-paired-progress.json/md/csv`。
最新完整配对计数为 12/16，目标保持进行中。

费用导出修正：复用旧 provider 首轮回复的尝试，在表格标记为仅续跑新增费用（†），
不将复用的生成视为免费完整方法；原始费用和累计费用仍保留在尝试记录。

最近一次终止实验缓存清理：batch-a/I2C 1,115,922,399 字节，
batch-b/simple_spi 582,231,358 字节；材料校验一致后仅删除可重建缓存。

## 15:12 UTC 无模型调试：固定配置输入与离线回放工具

`batch-d/sdram` 已结束：HAVEN 两轮有效后第三轮失败（92.95977%、751,270
tokens、789.994 秒）；RVProbe 三轮完成（89.22414%、3,089,983 tokens、
2,978.720 秒）。配对仍失败，不新增完整设计计数。全部材料已归档到
`/var/storage/workspaces/clo91eaf/rvprobe-independent-paired-20260911-YuflGB/batch-d/sdram`，
校验一致后清理 227 项可重建缓存，共 2,020,379,701 字节。

定位：HAVEN `round-3-repair-1` 的第四条序列明确请求将 cfg 配置扫描为全零、全一。
这些端口在双方冻结环境中是 static（例如 cfg_sdr_width=1、cfg_sdr_en=1）。
原适配器暴露字段时没有说明固定值，且 soft 默认全部为零；顶层仍按环境驱动常量，
导致候选违规被后置 INPUT_WITNESS 检查误分类为框架传输故障。

修复不改变 DUT、Stage-1、固定值、BFM、HAVEN prompt 模板或模型输出：

- 共享字段元数据说明 raw 模式下的静态输入前置条件；构造及 soft 默认使用环境值。
- 保留显式冲突请求（不从 DSL 丢弃、不改写为固定值），驱动任何引脚前报
  CANDIDATE_ENVIRONMENT，允许按固定环境修复候选；INPUT_WITNESS 仍保留，
  真正的传输不一致仍禁止模型修复。
- 仿真端强制前置条件，不依赖调用方是否检查 randomize() 的返回值。
- 比较新旧 bundle：原 RTL/Stage-1 源哈希、replay 环境、BFM、baseline 全部相同；
  公共组件仅 driver、seq_item 有变化，两边使用同一个 bundle。

零模型验证记录均位于上述运行根：

- `static-transport-v2`：通用合成正反例通过，51 个采样事件、102 次输出检查；
  验证构造默认、randomize 省略值、显式冲突保留及驱动前拒绝。v1 的诊断夹具
  声明顺序编译错误保留；只修夹具后重跑 v2，不计入实验。
- `static-sdram-negative-v1`：原 HAVEN 第四条序列不改写，7.506 秒后按预期以
  candidate_environment_violation 拒绝；实际 summary 保留 failed，不伪装候选成功。
- `static-sdram-haven-positive-v1`：原 HAVEN 第二轮第一条合法序列通过，12.034 秒。
- `static-sdram-rvprobe-positive-v2`：原 RVProbe 第三轮第一条通过，13.591 秒；
  784 个采样事件，原始 `mode_register_address_then_init_done` Cover 在原生四态
  仿真中命中。不是整个输出波形等价的声明。
- 顺带修复 `replay_saved_candidate.py`：单独读取后续轮次时原 witness 序号不是零；
  先按保存的全局序号严格校验完整生成源码，再仅重排诊断编号。输入/时间/LTL/
  检查均不变，原候选文件不覆盖；正式配对校验器不放宽。首次 v1 失败保留。
- 最新单元测试 358 项：342 通过、16 跳过，见 `framework-tests-static-v2.log`。

已启动 `offline-ethmac-encoded-v2`，复用未修改的真实 provider 首轮 UT，重新执行
完整当前流程，禁止新模型调用；完成前不计入正式配对。CAN 上一轮 v3 父进程已
消失（旧 running 文件不是活跃证据），需后续新离线尝试；暂不并发启动以控制临时盘。
当前完整配对仍为 12/16。

## 16:12 UTC：ETHMAC 完整无模型验证通过

15:59 再次检查确认 ETHMAC 离线 v2 和 CAN 离线 v3 没有存活进程，且没有终态
summary；旧 running 文件不能视为运行证据。各自新增 `interruption.json` 保留
中断观察，不覆盖原记录、不归因 DUT/LTL。归档一致后分别释放 603,737,250 和
204,077,673 字节的编译缓存。旧正式 batch-j/simple_spi 也在归档一致后释放
1,038,501,914 字节；所有模型回复、波形、覆盖数据库和成本材料保留。

`offline-ethmac-encoded-v3` 使用独立进程会话直接启动，无定时任务；已完整通过：

| 原模型意图 | 原生通过序列 | 检查的不同候选数 |
|---|---:|---:|
| reg_region_write_partial_lane_ack | 4 | 4 |
| unmapped_address_write_error_response | 4 | 4 |
| no_byte_lane_read_error_response | 4 | 4 |
| bd_word_write_readback_value | 4 | 20 |

最后一项为 16 个普通候选被原始 Cover 拒绝、4 个辅助候选经原始四态 RTL/Cover
验证通过。没有修改保存的真实 provider UT、没有放宽原始 Cover 或输入检查。
共 16 条，完整覆盖合并成功，诊断综合覆盖率 91.1577968295%，耗时
661.052941 秒，零新模型调用/token。该结果证明此保存回复的完整框架流程，
不计为正式配对，不代表任意新模型回复都能通过。

已直接启动 `offline-can-encoded-v4`，同样复用保存的真实 UT；进程身份保存在
`launch-identity.json`。同时启动新的 `batch-e/simple_spi` 正式双方配对，使用
`stage1-map-e.json` 中原冻结 Stage-1，模型请求名 deepseek-v4-flash-vision-exp，
三轮预算、每意图最多四条、无 Stage-1 模型调用。未复用付费回复，本次成本单独计。
该正式尝试与离线检查有时间重叠，因此耗时不是独占机器的性能基准；并行关系保留。
运行和归档根仍为 rvprobe-independent-paired-20260911-YuflGB。

## 16:30 UTC：CAN 完整无模型验证通过，继续正式配对

`offline-can-encoded-v4` 完整通过，耗时 1,035.334809 秒，零新模型调用/token：

| 原模型意图 | 原生通过序列 | 检查的不同候选数 |
|---|---:|---:|
| wb_addr_out_of_window_access_and_request_term_conditions | 4 | 4 |
| tx_request_drives_dominant_can_bit_on_recessive_bus | 4 | 20 |
| acceptance_code_write_then_acknowledged_read_back | 4 | 20 |
| dominant_rx_error_flag_then_bus_release | 4 | 20 |

后三项均为 16 个普通候选被拒绝、4 个辅助候选通过原始四态 RTL/Cover。全部
16 条经过最终覆盖合并，诊断综合覆盖率 97.8789251208%。原 provider 回复、
LTL、Stage-1 保持不变。这不是正式双方闭环，不增加完整配对计数。

`batch-e/simple_spi` 的 HAVEN 已完成三轮，RVProbe 前两轮各 16 条均通过，
正在进行第三轮，尚未计完成。ETHMAC/SDRAM 的 `batch-f` 启动被准入检查正确
拦截：误选了 domain-refresh 的诊断成本记录。已找到原有
`fixed16-environments-20260910-v4` 共享登记，验证其 Stage-1 与离线验证使用的
完全相同；改用登记路径后启动 `batch-f2`（ETHMAC 运行、SDRAM 顺序排队）。
原 `batch-f.log` 与 `batch-f-startup-failure.json` 保留，启动失败零模型调用，
没有改写 Stage-1 或放宽准入检查。当前和登记快照的 HAVEN 实现哈希一致。

为运行腾空间，另在归档校验一致后释放以下旧终态尝试的编译缓存，材料保留：
offline-uart-aggregate-v1 1,047,330,795 字节；
rvprobe-saved-ethmac-fix-20260911-v1 1,013,687,068 字节；
rvprobe-saved-sdram-fix-20260911-v1 708,707,303 字节。
本轮无新源代码改动；沿用此前已经通过测试的框架修复，完成全流程验证。

## 16:40 UTC：simple_spi 正式配对完成，13/16

`batch-e/simple_spi` 两侧均正常结束，三轮预算；原冻结 Stage-1 和同一个
independent-dut-v1 环境，显式启用有界辅助候选搜索，每条 RVProbe 序列均经
原始 RTL 的原生 Cover 验证。此次不复用付费回复，以下为该完整尝试的费用。

| 方法 | 综合覆盖率 | 有效轮次 | 新增序列 | 总 token | 运行秒数 |
|---|---:|---:|---:|---:|---:|
| HAVEN | 91.5930066713% | 3 | 11 | 170,781 | 449.124876 |
| RVProbe | 91.9845410628% | 3 | 44 | 601,460 | 1,506.748757 |

请求模型名 deepseek-v4-flash-vision-exp，服务端实际报告 deepseek-flash；不能仅凭
请求名断言后端模型身份。RVProbe 每轮序列数 16、16、12，三轮首次回复都完整、
首次 UT 编译/求解通过。保留普通候选拒绝记录，辅助候选不是原始证明的替代。
双方 stop_reason=round_budget。归档由批次执行器正常完成，没有 archive_error。
此尝试与离线检查及其他设计并行，时间为实测共享机器墙钟时间。

正式完整计数更新为 13/16。`batch-f2` 正在运行 ETHMAC（之后顺序 SDRAM），
`batch-g` 正在运行 CAN，均使用同一份各自冻结的共同 Stage-1，两个批次通过
独立进程会话直接启动，不使用定时任务。CAN 的正式登记也来自已有 v4 登记，
与无模型验证完全相同。新成本记录和失败尝试继续保留。

随后观察：`batch-f2/ethmac` 的 HAVEN 在第二轮修复候选
`ethmac_gap_defer_toggle_seq` 的 `wait_txb_or_txe_1` 轮询超时，耗尽当前修复预算，
该侧状态 failed（仅一轮有效），RVProbe 侧继续执行。本次错误是候选运行时轮询
超时，没有 INPUT_WITNESS/原始 Cover 回放错误；具体超时根因尚未证明。
保留原始候选，不手工删除 poll 或改写 DSL。该批次即便 RVProbe 完成也不能计完整
配对，需要进一步诊断/后续正式尝试。CAN 正式侧仍在推进。
simple_spi 配对归档校验一致后释放编译缓存 2,015,422,347 字节；
CAN 离线 v4 归档后释放 1,941,328,704 字节，材料保留。

## 2026-09-12：停止状态确认与存储路径调整

00:27 UTC 检查未发现实验执行进程，不能继续使用旧 progress.json 的 running
作为存活证据。正式配对完成数仍为 13/16。

- CAN batch-g：HAVEN 完成 3 轮，综合覆盖率 96.8297101449%；RVProbe 第一轮
  generation/summary.json 报 model dialogue call budget exhausted，未接受新轮次。
- ETHMAC batch-f2：HAVEN 第二轮发送状态轮询超时；RVProbe 第二轮
  generation/summary.json 同样报模型对话调用次数预算耗尽。两侧各接受 1 轮。
- SDRAM batch-f2：HAVEN 完成 2 轮，92.9597701149%；RVProbe 接受 2 轮，
  89.4157088123%，第三轮没有形成完整配对终态。不把部分求解成功计成整轮成功。

batch-f2.log 明确记录保存进度时 OSError: [Errno 28] No space left on device；
复查 /dev/shm 为 16 GiB tmpfs、100% 使用。该错误证明批处理发生存储故障，
不把此前独立的模型预算耗尽或 HAVEN 超时一概归因于磁盘。

按用户再次指定，后续实验主目录使用 /var/storage/workspaces/clo91eaf；实际为
NFS 挂载，不能将迁移本身视为 EDA 兼容性已验证。保留所有旧路径来源记录、
失败尝试和模型成本；当前开始复制 batch-f2/sdram 全部残留文件至该目录下
同名批次，不覆写失败结果、不手工改写 LTL、不调用新模型。

迁移进展：SDRAM 共 9,864 个普通文件、1,955,939,679 字节（含链接长度）复制完成，
rsync -anc 内容校验退出 0、无差异；旧目录仍保留，便于解析历史绝对路径。
CAN 和 ETHMAC 的终态与归档材料经 prune_archived_caches.py 哈希校验一致后，
仅删除可重建缓存，分别释放 561,600,909 与 1,889,524,034 字节。
复查 /dev/shm 可用 2.4 GiB，使用率 85%。此操作未启动新实验或模型调用。

## 2026-09-12 01:32 UTC：batch-h 启动剩余三设计

用户随后明确允许在 /dev/shm 运行、再迁移至 /var/storage/workspaces/clo91eaf。
修改已实现于 experiment_storage.py 和 HavenSimulation：每次仿真关闭后（含失败）
归档且核对全部非缓存材料后，仅删除 csrc/simv/simv.daidir 等可重建缓存。
原路径覆盖数据库、日志、输入与原生 Cover 证据仍保留。storage-checkpoint.json
只证明 IO 已关闭，不代表仿真或配对成功。批次启动要求至少 1 GiB，每次仿真至少
256 MiB 可用空间；这只是提前防护阈值，不保证整个任务容量。归档过程计入两侧墙钟。

CAN/ETHMAC 失败对话仍在最后一次请求中读取 RTL；并非已经生成完整 UT 后的编译错误。
rvprobe_skill.py 现保留原 24 次请求总预算的最后一次，使用 tool_choice=none 要求
根据已返回证据给出完整 JSON；不追加请求、不注入答案、不更改 HAVEN prompt。
不保证模型最终答案正确或不会截断，原验证门槛与费用记录保持。

回归：363 项测试，347 通过、16 跳过，framework-tests-storage-finalslot.log。
真实无模型存储冒烟 storage-smoke-sdram-v1：14.655014 秒，原已保存第二轮候选第 1 条
idle_write_handshake_then_cke_high_write_command 通过四态原生 Cover 回放。
确认仿真目录已归档、编译缓存释放、simv.vdb 保留。此项不是正式配对结果。
旧 batch-f2/sdram 的进程缺失与 ENOSPC 另写 interruption.json，未覆写原进度或成本；
归档核对后释放 1,566,006,144 字节缓存；启动前 /dev/shm 可用约 3.8 GiB。

batch-h 直接 setsid 启动，PID/SID 2642905，01:32:26 UTC 进入 SDRAM running_both，
随后顺序 CAN、ETHMAC；无定时任务。两侧均重新运行，旧失败尝试全部保留。
继续使用 stage1-map-f2.json 中已验证的 v1 SDRAM、v4 CAN/ETHMAC 登记，
不重建 Stage-1、不复用历史答案。模型请求 deepseek-v4-flash-vision-exp，
实际服务端名、费用与耗时继续由执行器逐请求记录。正式完成数暂仍 13/16。

## 2026-09-12 04:32 UTC：及时释放历史 tmpfs 副本

按用户要求，清理前核实 batch-h/ethmac 仍运行，不触碰其工作目录。
batch-h 的 SDRAM/CAN 已结束，但最终 copytree 与逐次归档的现有 VDB 软链接冲突；
使用 rsync 补齐终态归档后，prune_archived_caches 校验材料一致，保留原 archive_error
作为真实失败记录。archive_tree 新实现跳过目标一致的既有软链接，冲突目标仍报错，
修复未来重复归档；已经加载旧代码的 batch-h 父进程不声称已热更新。

新增 --relocate：只允许终态且非缓存材料全部校验一致的目录，转为持久化归档软链接，
删除已校验的 tmpfs 重复副本，不删除归档。保持历史绝对路径可读。
本次完成：batch-f2/sdram（interruption.json 审计终态）、batch-f2/ethmac、
batch-d/sdram、offline-can-encoded-v4、offline-ethmac-encoded-v3。
相关 10 项存储/归档测试通过；复查 /dev/shm 可用空间从 2.7 GiB 增至约 5.3 GiB，
使用率从 83% 降至 66%。所有 witness、日志、覆盖数据库和模型成本在归档中保留。

## 2026-09-12 05:01 UTC：辅助模型的显式时钟 past 修复

batch-h 已于 04:33:22 UTC 结束，三个配对均失败，完整计数仍为 13/16。
CAN 的 tx_request_ack_then_recessive_bus_overridden 与 ETHMAC 的
tx_bd_num_write_readback 均在 without-cover.sv 的 `$past(expr, N, , @(posedge clock))`
中报 unexpected ','：JG/VCS 接受的显式时钟采样语法直接进入 Yosys 前端，而辅助路径
此前没有处理该语法。这不是模型 Scala 编译失败，不应调用模型修复该 LTL。

新增 encoded_past.py：仅辅助模型将非门控、常数深度、正沿已声明时钟的 past
降成等宽历史寄存器，支持嵌套；不支持的门控/时钟形式明确失败。原始 SV、模型 UT、
原生 Cover 不修改。历史不足时保持辅助未知值，禁止任意填 0；这可能比 native 的
初始默认采样更保守，不宣称前历史阶段完全等价或原始目标不可达。
past-lowering.json 与实现 SHA 记录这一候选模型限制。

直接重用 batch-h 的原始求解产物，无任何新模型调用：

| 检查 | 辅助求解 | 原始四态 Cover 回放 |
|---|---:|---:|
| past-can-v1 / past-can-replay-v1 | covered，14.392777 秒 | passed，13.239963 秒 |
| past-ethmac-v1 / past-ethmac-replay-v1 | covered，29.983381 秒 | passed，12.484973 秒 |

原始源码哈希由回放结果保存，未手改答案。上述只是每个意图一条通过，不是整轮配对。
通用 VCS 回归 past-equivalence-v4 通过：36 次快时钟采样、多拍/嵌套/慢时钟、X/Z
数据；在历史有效后与 native 相同，之前单独检查辅助状态未知，并含不同深度负对照。
v1 测试暴露寄存器前向声明位置问题，已修；v2/v3 保留为 native 前历史默认值差异证据，
不改成虚假通过。所有这些日志已归档并清理可重建缓存。
Python 全套 370 项，354 通过、16 跳过（framework-tests-past.log）。

05:01 UTC 直接启动完整无模型回归 offline-can-past-v1（PID 2872360）、
offline-ethmac-past-v1（PID 2872383）：各读取 batch-h 原始完整回复，正常编译/求解/
每意图最多 4 条采样/原生验收/整轮覆盖合并；固定 Stage-1，硬禁止远程模型。
当前进程已进入采样，未完成。存储使用自动仿真归档与缓存清理。

另查明 SDRAM 第二轮缺少 4 条的具体原因：16 个辅助搜索都 covered，但索引 1–15
的 inputFingerprint 完全相同，只有两条不同且原生通过的输入，不是 14 条 native
回放再次失败。配置中有 13 个固定输入，随机软偏好仍对其生成冲突请求；这是后续
采样效率排查方向，尚未证明其为全部重复的根因，不通过降低验收数量掩盖问题。

## 2026-09-12 05:18 UTC：真实 wrapper 位宽依赖回归修复

offline-can-past-v1 和 offline-ethmac-past-v1 均已失败，分别 494.509686 秒和
382.232433 秒，无模型调用。此次不再是 $past 语法错误，而是上轮将历史寄存器
统一提前至模块开头，导致 `$bits(_tx_o_output)` / `$bits(_GEN_1)` 引用了尚未声明
的内部线网，Yosys 无法推断位宽。单意图检查使用更早的声明尾置版本，通用 VCS
测试使用提前版本；前一轮检查没有同时覆盖实际内部线网依赖，这是回归范围遗漏。

encoded_past.py 现按原连续赋值语句位置插入历史声明，位于已有依赖之后、消费者
之前，嵌套历史维持依赖顺序。past-equivalence-v5 增加内部 wire 和历史 wire 链，
同一输出先由 Yosys 解析/降级，再由 VCS 与原始时钟 past 比较，5.279854 秒通过。
Python 全套 372 项、356 通过、16 跳过（framework-tests-past-order.log）。
失败 v1 回归已归档、校验并清理缓存，原始失败记录不覆写。

同时，辅助 soft-preference 的候选输入列表改为自动排除冻结 environment.static
中的端口，不改变任何固定值或原始 LTL。记录 policy=variable-input-soft-preferences-v2
及具体可变输入名称；不宣称此修改已经解决所有重复 witness。
以原 SDRAM 552-cycle 证明长度和既有 seed=20260907 启动 sdram-variable-pref-v1，
截至记录时仍在 JG 重采样，尚无结果，不增加原采样预算或接受重复输入。

offline-can-past-v2（PID 2951164）与 offline-ethmac-past-v2（PID 2951187）已直接
启动全流程无模型回归，仍用 batch-h 的原始回复与冻结 Stage-1。当前仍运行；
不作为正式新配对结果，完整数仍 13/16。

## 2026-09-12 05:28 UTC：CAN 无模型整轮通过，SDRAM 排重取得有效新输入

offline-can-past-v2 已通过，562.762411 秒、0 新模型调用/token，4 个意图各 4 条，
共 16 条新增 sequence，整轮合并覆盖 97.8789251208%。前三意图各 4/4 普通候选，
tx_request_ack_then_recessive_bus_overridden 为 16 个普通候选拒绝后 4 个辅助候选
全部原生通过。声明依赖顺序修复已通过真实完整 wrapper/采样/回放/覆盖流程。
这不是完整正式配对；启动 batch-i/can 正式双臂重跑（PID 3063084），继续使用
原冻结 v4 登记与请求模型 deepseek-v4-flash-vision-exp，按实际响应记录模型名与费用。

SDRAM sdram-variable-pref-v1：178.724974 秒，covered 且 native passed，但输入
指纹仍为原来的 509c5806...，因此排除静态输入并没有解决重复，保留该反例。
新增可选 --avoid-stimulus，读取历史已求解输入，在自动选出的一个可变端口/采样点
添加“不同于已有值”的候选子集约束，不修改 DUT、LTL 或时间长度，不宣称子集失败
意味着原目标不可达。sdram-distinct-pref-v1：175.877069 秒 covered，native 回放
16.001475 秒 passed，新 inputFingerprint=33a353ff82a78e12d11091b6522c94e7876b4b507de87dc540e2422bb186451a，
与原先两条都不同。原始 LTL 源码 SHA 和原生 Cover 仍一致。

encoded_candidate_search 现仅在实际发现重复后启用排重子集，仍共用原 16 次辅助预算；
子集无解/未定消耗一次预算后换下一个确定性采样点，不扩大预算、不降低目标 4 条。
新全套回归 374 项、358 通过、16 跳过（framework-tests-distinct-integration.log）。
offline-sdram-distinct-v1（PID 3055343）已启动整轮无模型验证，尚未通过；
offline-ethmac-past-v2（PID 2951187）仍运行。不要将单个排重新 witness 计为整轮成功。

### 2026-09-12 05:37 UTC：ETHMAC 无模型整轮通过，正式双臂继续

offline-ethmac-past-v2 已通过，966.399961 秒，0 新模型请求、0 新 token；
读取 batch-h 原始完整回复，4 个意图共新增 16 条 sequence，总数 25，
诊断整轮综合覆盖 89.6426453144%。这不是正式配对，也不包含后续两轮的通过保证。
全部材料已同步到归档并逐文件校验，工作目录改为归档软链，清理的是已验证副本和
可重建缓存；原绝对路径仍可访问，/dev/shm 当前剩余约 5.5 GiB。

启动 batch-j/ethmac 正式双臂（PID 3104647），05:36:29 UTC 状态 running_both。
沿用注册冻结 v4 Stage-1、原 HAVEN prompt、3 轮预算、每意图 4 条，
请求模型 deepseek-v4-flash-vision-exp，实际响应模型与费用单独记录。
CAN batch-i 仍运行 HAVEN 第 3 轮（已接受 2 轮）；SDRAM offline-sdram-distinct-v1
仍在无模型整轮验证，未宣称通过。正式完整配对仍为 13/16。

### 2026-09-12 07:50 UTC：终态审计、闲置 BFM 隔离修复与新尝试

batch-i/can：RVProbe 正常完成 2 轮后 coverage_stalled，97.8449577295%，
1,671,959 token、1716.581194 秒；HAVEN 接受 2 轮，96.6259057971%，
482,179 token、596.025959 秒，第 3 轮响应 finish_reason=length。
该响应 completion_tokens=65535，其中 reasoning_tokens=56144，JSON 中途截断。
请求没有显式 max_tokens；不能据此宣称 65535 是服务端可配置上限。
覆盖停滞本身正常；配对失败仅因 HAVEN 未正常终止，不将 RVProbe 标成失败。

batch-j/ethmac：HAVEN 接受 1 轮，90.2573331177%，1,291,576 token、
1069.668430 秒，随后 wait_rx_oversize 轮询超时；RVProbe 接受 2 轮，
93.3449261296%，7,092,004 token、3957.729482 秒，第 3 轮原生候选回放触发
BFM MEMORY_ADDRESS（00000000xxxxxxxX）。两侧均未完成，不计正式配对。
实际响应模型均记录 deepseek-flash，请求模型仍为 deepseek-v4-flash-vision-exp。

offline-sdram-distinct-v1 通过：1709.186230 秒、0 新模型/token，新增 12 条
sequence。它是 batch-h 第二轮原始完整回复的独立诊断，不包含前轮覆盖累计，
不将其 84.5785440613% 与原正式第二轮累计值直接比较。

上述三个已结束工作目录全部校验归档后替换为归档软链；保留全部非缓存材料与
原绝对路径，释放已验证的临时副本。07:50 UTC /dev/shm 剩余约 5.5 GiB。

ETHMAC 框架缺陷：independent-dut-v1 虽切断 BFM 对 DUT 输入的驱动，
却继续让 BFM 处理原始 DUT 请求，闲置 Wishbone 模型因未知地址退出。
event_transport 现在仅在独立 raw 模式下将外部 BFM 的 reset 置有效；
原生模式保持原连接，共享环境模式不变，DUT 输入、原 LTL、Stage-1 文件均不改。
这是双方共用的模式切换修复，不是单侧删除检查。测试覆盖正负复位极性和三个
响应 BFM；Python 375 项（359 通过、16 跳过）。VCS wishbone wait=0/3 均通过：
raw 模式未知地址请求不服务、不改变内存，切回 native 后非法地址仍被拒绝，
正常读写仍通过（7.511634 秒 / 6.360763 秒，0 模型）。

启动 offline-ethmac-inactive-bfm-v1（PID 3430267），复用 batch-j 第 3 轮
原始完整回复做整轮无模型回归，尚未通过。
启动 batch-k/sdram（PID 3430268）与 batch-l/can（PID 3445838）新正式双臂。
固定原注册 Stage-1，HAVEN prompt 不改，仍 3 轮、每意图最多 4 条。
CAN 为保留旧截断结果的新尝试，不续写残缺响应、不拼接不同尝试的成功臂。
完整配对计数仍为 13/16，跟踪表已登记两次新正式尝试。

### 2026-09-12 07:58 UTC：原失败序列的精确回放与继续运行

三条运行句柄（3430267、3430268、3445838）均核实存活，未重复启动。
SDRAM HAVEN 已接受 2 轮，CAN HAVEN 已接受 1 轮，后续轮次仍运行。
额外将 batch-h/can、ethmac、sdram 三个已结束目录逐文件核验后迁移为归档软链，
保留全部原始路径和非缓存材料；此时 /dev/shm 约 6.1 GiB 可用。

replay_saved_candidate 新增 --simulation-directory，读取已有 inputs.json 中的
sequence 指纹、原 sequence_N.sv 与 schedule.json；只重放原输入，不生成 LTL。
序列内容必须匹配原指纹及原调度。已隔离的仿真沿用本地 ordinal=0，不能将
历史 segment ID 误当成本地 ordinal；普通完整候选的全局 ordinal 校验仍保留。
v1/v2 在诊断加载阶段拒绝了指纹算法/ordinal 解释不一致，日志保留，0 模型。

inactive-bfm-exact-replay-v3 重放 batch-j/ethmac 原始失败缓存 e8484db0...：
10.365519 秒，输入/时序 replay-checks.passed=true，TEST_DONE 到达，
UVM_ERROR=0、UVM_FATAL=0，原 BFM MEMORY_ADDRESS 不再发生。
原始 Cover 仍 0 match，故诊断保留 failed / formal_replay_semantics_mismatch；
这只证明闲置设备中止问题已消除，绝不把该候选计为有效 witness。
offline-ethmac-inactive-bfm-v1 仍通过正常预算寻找原生有效候选，完整回归尚未完成。

### 2026-09-12 11:43 UTC：保留 HAVEN 失败结果，启动最终 ETHMAC 配对

用户决定不再为 HAVEN 的截断/轮询超时反复重跑；将 CAN/SDRAM 这些终态失败
作为实验结果保留。结论限定在本次模型/提示/预算/环境配置下的鲁棒性观察，
没有 GPT-5.4 同条件对照，不能把模型能力相对排名或失败因果当作已验证事实。
报告增加 terminal_pairs（含失败，现 15/16），complete_pairs 仍表示双方成功
（13/16），不改任何失败臂的原状态。6 项报告回归通过。

CAN batch-l：H failed（第 2 轮截断），接受 1 轮，96.5919384058%，
323,573 token、436.026087 秒；R completed（coverage_stalled），2 轮，
97.8789251208%，2,832,065 token、2712.386463 秒。
SDRAM batch-k：H failed（第 3 轮轮询超时），接受 2 轮，92.9597701149%，
774,925 token、953.845068 秒；R completed（round_budget），3 轮，
89.7030651341%，4,256,257 token、7406.505804 秒。

offline-ethmac-inactive-bfm-v1 已于 08:16:57 UTC 通过：4 个意图各 4 条，
16 条新增 sequence，诊断覆盖 94.0977838887%，1736.149710 秒，0 新模型/token。
启动 batch-m/ethmac 正式双臂，PID 3807791；11:43 UTC 核实 running_both、
stage1_model_calls=0。原注册 v4 Stage-1、HAVEN prompt、模型请求名、轮次/采样预算
均保持；这是新正式尝试，不把离线回归算进正式费用或完整配对。

fixed16-paired-progress 已重新导出 MD/CSV/JSON，除正在重跑的 ETHMAC 外列出
15 个设计；轮次指已接受闭环迭代，不是 testbench poll 次数。所选尝试费用含
该尝试失败消耗，但不是全部历史重试之和；各历史尝试仍在 JSON 内。
AXIL RAM 仅存续跑增量 token、缺少时间，不能将 0 token 解释成完整生成免费。

### 2026-09-12 13:25 UTC：按用户确认的终态口径收尾

用户明确：HAVEN/RVProbe 的截断、超时、预算内解不出来均为正常实验终态，
只需如实记录，不要求反复重跑至成功。因此本轮 16 个设计配对结果均已结束，
其中 13 个双方 completed；CAN/SDRAM 的 HAVEN 与 ETHMAC 的 RVProbe 保留 failed。
没有修改判据、LTL、搜索预算或原始失败状态；并不宣称两侧全部成功。

ETHMAC batch-m：H completed，3 轮，91.1995848161%，986,057 token、
622.541794 秒；R failed/native_witness_search_exhausted，接受 1 轮，
91.2467648010%，3,974,012 token、4827.576070 秒。
第 2 轮 tx_bd_num_value_rewrite_then_read_back 在 32 个不同候选内仅接受 3/4。
这是预算内候选不足，不据此证明原目标不可达，也不将其自动归为框架缺陷。
13:15:55 UTC 批次正式结束，Stage-1 调用 0、冻结校验无错误、归档无错误。

最终 fixed16-paired-progress.json/md/csv 已更新为 16 个终态；CSV 补充
stop_reason/error/reported_models，JSON 保留所有登记历史尝试及实际用量。
CAN 的 HAVEN 停止原因另见其 incomplete-response 记录（finish_reason=length）；
SDRAM 的 HAVEN 为已记录的 Poll timeout；不主张未经对照验证的模型能力因果。
已知数据限制仍明确保留：历史版本混合，AXIL RAM 的完整成本与耗时缺失，
所选尝试成本不是全部历史重试总成本。不会补造缺失数据。
