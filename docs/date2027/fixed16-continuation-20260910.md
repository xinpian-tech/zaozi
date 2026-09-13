# 16 设计完整配对续跑（2026-09-10）

目标：补齐未完成的 HAVEN Stage-2 / RVProbe 配对。Stage-1 是双方共享环境，不按 Stage-2 模型重新生成。人工 LTL 流程诊断不计为模型实验。

## 实验存储迁移（2026-09-11）

08:57 UTC 完成 8 个顶层 `rvprobe*` 归档目录向
`/var/storage/workspaces/clo91eaf/` 的迁移。源和目标是不同 NFS 挂载，
逐目录复制后使用 `rsync -aHnci --delete` 校验内容、元数据和链接；
校验无差异才清理源副本，原路径保留软链接。未改写历史实验记录。
迁移脚本、复制统计和逐目录校验日志保存在
`/var/storage/workspaces/clo91eaf/rvprobe-relocation-20260911-YhQy9v/`。
当前跟踪清单的 `archive_root` 已更新，后续新实验工作/归档目录使用该用户目录。
本次没有运行模型或 EDA，完整配对仍为 11/16。

## 当前：独立 DUT 边界离线修复（2026-09-11）

最新离线诊断将 ETHMAC 的失败定位到 RAM 三态输出经输出寄存器传播：
独立小电路复现 JG 对寄存器中的 Z 与 VCS 的语义差异，两个非悬空偏好原型
均未修通并已撤下。详见 [寄存器 Z 诊断](registered-z-diagnosis-20260911.md)。
本轮模型调用为零，正式配对仍为 11/16。

继续使用保存的真实模型响应离线重走编译/求解/原生补采样，仍未修通全部失败。
已修复失败时丢失波形诊断、补采样耗尽误报为共享环境故障两处问题；HAVEN
ETHMAC 的超时发现候选配置与目的 MAC 不匹配，未手工更改模型序列。
结果与未采用的诊断方案见 [保存响应离线修复记录](saved-flow-debug-20260911.md)。

随后按用户要求复测 5 个未完成设计的真实保存候选（不调用 DeepSeek、不补采样）：
I2C 两侧全过；CAN RVProbe 4/16、SDRAM 8/12、ETHMAC 12/16，ETHMAC HAVEN 3/4；
simple_spi 上一轮完整候选全过，但第二轮截断响应未补齐。95 条中 74 过、21 失败。
13 项真实工具链检查全部执行通过；修正旧 HAVEN 测试夹具后，315 项常规检查也通过。
详细来源与保留失败见 [未完成设计离线复测](pending-offline-20260911.md)。

09-11 07:48 UTC：SDRAM 的同一份人工诊断 UT 完整重跑通过，2 个 intent 各 4 条，
8/8 原始 Cover 命中。根因是形式解利用了悬空总线的未定义值；新增同 LTL 的有界
native 验证/补采样，读意图保留 10 条拒绝、接受 4 条。CAN 回归 8/8 通过，4 条负例
均拒绝；测试 328 项（312 通过、16 跳过）。模型调用为 0，正式配对仍为 11/16。
完整记录见 [SDRAM witness 修复](sdram-native-witness-fix-20260911.md)。

用户进一步要求不调用 DeepSeek，由助手填写完整 LTL 回答测试真实流程。
已完成 CAN/SDRAM 各一轮人工作者诊断：4 个 Gen 全部编译并求解成功；
CAN 8/8 sequence 通过，SDRAM 5/8 通过、3 条读数据样本回放失败。
没有自动运行付费模型；这些结果不增加正式配对完成数。
来源与复现记录见 [人工作者诊断](manual-author-20260911.md)。

用户已选择方案 1（独立 DUT），新批次入口默认 `independent-dut-v1`。
batch-j 已于 05:35:20 UTC 结束，服务退出；SPI 成功，其余 5 个配对失败，历史完整配对为 11/16。
不能直接重启旧策略。当前没有新的付费实验；先验证统一输入控制权。
I2C round-2-repair-1 的原 16 条 witness 全部通过，4 条禁用请求的负向测试全部拒绝。
CAN 的寄存器读回、SDRAM 的写后读回抽查仍未命中原始 Cover，需继续离线排查。
全套测试 321 项（305 通过，16 跳过）；本次模型调用 0；冻结 Stage-1/RTL 哈希未变。
实现、归档、未完成门禁见 [独立 DUT 记录](independent-dut-20260911.md)。

## 已结束批次 batch-j（2026-09-11）

01:34:48 UTC（UTC+8 09:34:48）通过独立 systemd service `rvprobe-fixed16-batch-j.service` 启动：
I2C → SPI → SDRAM → CAN → ETHMAC → simple_spi，双方完整重跑。没有定时器或自动失败重启；
服务负责进程生命周期，用户会话不再是父进程。监控以 `systemctl show` 的实际 MainPID/ActiveState 为准。
进程初始 PID 为 714005；工作/归档目录为现有根下的 `batch-j`。

6 个环境继续使用 batch-i 的固定 Stage-1 映射，原始记录及文件哈希全部校验通过，不生成 Stage-1。
HAVEN 的 prompt/模型请求不变。RVProbe 直接携带冻结 skill（`frozen-inline-skill-v1`），
只将本次已接受模型 UT 作为历史。`spec-io-on-demand-rtl-v2` 进一步把 gap 中的大段 report_section
独立为可按需读取的 coverage_reports，保留所有 gap/count/原报告哈希和全文，不把原始报告丢弃；
read_rtl 增加按行定位，仍可用字符分页完整读取超长行。旧 simple_spi 第二轮的常用 coverage 上下文
降到 6,067 字符；没有通过缩短历史结果、删除条件或修改 BFM 达到这一变化。

318 项离线测试：302 通过、16 跳过。模型请求仍为 deepseek-v4-flash-vision-exp，3 轮、每 intent
最多 4 条 RVProbe sequence；没有新增输出预算参数，因此不声称已解决供应商推理截断。
启动时完整配对仍为 10/16，所有新路径追加跟踪清单，旧失败及费用保留。

## 新批次 batch-i：仅 RVProbe 改为按需读取

用户确认启动新实验后，2026-09-10 23:53:31 UTC（UTC+8：9 月 11 日 07:53:31）启动双侧顺序队列：
simple_spi → I2C → SPI → SDRAM → CAN → ETHMAC。工作/归档根仍沿用下述根目录，子目录为 `batch-i`。
启动时已验证无旧实验进程运行，6 个固定 Stage-1 的原始记录和文件哈希均通过检查。
进程 PID 初始为 666868，执行会话为 89032；监控时必须重新核实进程存在，不能仅凭本记录判断仍在运行。

HAVEN 保留原 prompt、完整 RTL/反馈及无工具请求；仅 RVProbe 使用 `spec-io-on-demand-rtl-v1`。
共享证据仍为 `common-evidence-v6`，双方共用同一固定环境。模型仍请求 `deepseek-v4-flash-vision-exp`，
3 轮、seed 20260906、每 intent 最多 4 条 RVProbe sequence，所有工具往返和失败成本保留。
本批不使用人工 LTL 或保存的历史 Stage-2 答案，不重建 Stage-1；对上下文访问方式差异明确记录。

Stage-1 映射：`out/experiments/fixed16-rvprobe-on-demand-stage1-map-20260910.json`。
6 个新结果路径已追加到跟踪清单，未覆盖旧失败尝试。启动时完整通过仍为 10/16，不把排队或运行中当作完成。
各设计结束后由批处理归档；后续需核对空间并在归档验证通过后按原规则清理可重建缓存。

## 实验边界

- 请求模型 `deepseek-v4-flash-vision-exp`；当前响应报告 `deepseek-flash`，保留二者，不将别名映射当作已验证事实。
- 双方使用同一冻结 Stage-1、RTL、BFM、基线、真实覆盖统计和终止策略；3 轮，seed 20260906，RVProbe 每 intent 最多 4 条 sequence。
- 新实验 Stage-1 模型调用为 0。历史准备成本保留、不重复计入 Stage-2；失败的 Stage-2 尝试照常记账。
- 已有手工配置的共享环境可以显式登记，但保留 `manual-diagnostic.json` 及哈希、原始作者记录。仅环境被采用，不复制人工 LTL 答案。
- 旧人工 Stage-2 实验仍是 diagnostic；普通入口仍禁止把它无声明改称 provider benchmark。
- 原始源码、基线、来源记录、共享 HAVEN 实现和 RTL 在运行前后校验；实验入口没有 Stage-1 生成或修复调用。

## 本次环境工作

新增显式共享环境登记和冻结核验，以及无需模型的旧环境元数据迁移入口。迁移只更新 CIRCT/JG 导出的 IO/时钟信息与通用组件；原编译 sequence 按字节复用。

最初选取的 design 1–5 原始检查点缺少 `clock_schedule`；离线迁移均通过编译，但准备检查仍发现旧 AXI item 契约与直接协议元数据缺口。这些失败不算配对完成，记录保留在 `out/experiments/fixed16-environments-20260910-v{1,2}`。

timer 最初选取的是更早的 7 序列检查点，尝试复用后来的 item 时编译指出旧 subscriber/sequence 依赖旧枚举，未调用模型。后改用先前成功运行的 `design5-rvprobe-current-20260909/stage1-checkpoint`，保留其 6 条基线和匹配组件，离线迁移、编译及 bundle 准备通过。最终注册位于 `fixed16-environments-20260910-v3`。

direct 事件元数据没有显式 request 协议时，保留原生 driver，不从信号名猜测请求协议，也不发生 KeyError。只有提供明确 request 元数据的旧循环协议才应用对应握手修复。

## 当前运行入口

工作根：`/dev/shm/rvprobe-fixed16-20260910-30xOp6`；本机可执行内存盘，避免 VCS 网络存储错误。

归档根：`/var/storage/workspaces/rvprobe-fixed16-20260910-30xOp6`；每设计终止后归档源码、日志、witness、覆盖数据库和用量，不复制可重建 csrc/simv 缓存。

- batch-a：simple_spi、spi、ue_gpio、ue_spi、ue_uart、sdram；双侧。
- batch-b：uart、can、ethmac、i2c、gpio；双侧。
- batch-c：ue_timer；双侧。
- batch-d：UART 失败后，在整批诊断修复版本下双侧重新运行；不复用旧 HAVEN 成功结果冒充新框架完整配对，原失败成本保留。
- batch-e：simple_spi 在 common-evidence-v4、整批诊断和 DSL 本地变量提前校验下双侧重跑。
- batch-f：common-evidence-v5 的双侧共享每轮 4 intent 上限版本，顺序重跑 CAN、simple_spi、UART；针对输出耗尽及分批反馈问题，不修改固定 Stage-1。
- batch-g：common-evidence-v6，顺序重跑 SPI、ethmac，补齐输出批次边界和固定 BFM 参数上下文。

历史已完整配对的 ALU、AES、SHA3、AXIL 保留原结果，不为本次补齐任务重新消费模型。GPIO 同时保留旧成功结果和本次新尝试。跨日期、版本的完成清单不能冒充同版本统一论文批次；每次框架哈希、环境和成本独立保存。并发运行的墙钟时间含资源竞争，不是隔离速度基准。

可更新工作区中的汇总：

```sh
python experiments/report_fixed_pairs.py \
  --manifest out/experiments/fixed16-tracking-20260910.json \
  --out docs/date2027/fixed16-paired-progress
```

只接受双方 completed 为完整配对。某侧因模型输出、真实 LTL 未满足或框架故障失败时，保留原目录，离线定位后在新目录重试，不能用诊断通过代替模型实验通过。

## 已定位的首轮真实拒绝

UART 首份远端 UT 编译、求解成功，但 `lsr_overrun_error_read` 在原生回放没有命中。离线读取其原始 JG VCD：初始 `u_rx_fifo/mem[0]` 被形式求解选为 99（0x63），FIFO count 始终为 0，`overrun_err` 始终为 0；一次地址 0 的空 FIFO 读取把任意初值带到 `wb_dat_o`，随后地址改为 5 时上一拍 ACK 仍高，数据保持 0x63。形式 witness 并非真正触发溢出，而是依赖未初始化数据和未约束的握手关系。四态原生回放正确拒绝，已进入模型预算内修正。未强制 FIFO 初值、未屏蔽输出、未降低 LTL 验收要求。

UART 修正版在 `loopback_roundtrip_rbr_read` 又未命中，首次续跑配对最终 failed，费用和所有产物已归档；没有算作完成。generic 诊断策略随后增加 `all-candidate-sequences-before-model-repair-v1`：双方均检查整个候选批次，汇总所有候选错误后再做预算内修复，任意错误仍拒绝整批。基线、基础设施及无法分类的异常立即停止，不交给模型修环境。成功/失败仿真均在相同候选内容下缓存，防止修复后重复编译相同坏序列导致目录冲突。原运行进程不热更新；新运行 manifest 显式记录诊断策略。

`prune_archived_caches.py` 先逐文件核对终止设计的归档，再只删除 `csrc`、`simv`、`simv.daidir` 和 Python 缓存；所有材料保留。不得对运行中设计或未完整归档的材料执行。编译缓存可以重新编译恢复。

整批诊断离线实测：`offline-uart-aggregate-v1` 在 303.826 秒内执行原始候选，发现 4 个失败 intent、16 条失败 sequence：`lsr_overrun_error_read`、`lsr_framing_error_read`、`iir_rx_data_available_read`、`lsr_data_ready_then_rbr_read`，均为真实 LTL 未命中。这是成功定位问题的诊断运行，不是实验成功。新模型调用为 0；原始 UT/sequence 没有修改。归档位于上述归档根的同名目录。

UART 首次续跑实际费用：HAVEN 90,978 tokens / 203.609 秒，RVProbe 138,527 tokens / 814.943 秒（最终 failed，0 接受轮）。不能将失败侧按这些数值声称覆盖完成。

common-evidence-v4 对两侧共同增加框架语义说明：输出相关意图需要实际握手和必要历史，先通过合法 IO 初始化将被观察的存储；形式两态任意初值不等于原生四态可回放事实。禁止把失败输出意图改成仅输入条件。没有注入任何设计名、寄存器数值或历史答案。DSL `register_read`/`poll` 的 store 指向未声明变量时，提前报出 sequence/step/变量而不是等待 VCS。

## 首个新完成配对：timer

batch-c/ue_timer 两侧各 3 轮、均 round_budget 正常结束。总墙钟 1640.852 秒（含共享基线）；HAVEN 132,385 tokens / 318.189 秒；RVProbe 189,471 tokens / 1277.865 秒。

| 方法 | Line | Condition | Toggle | Branch |
| --- | ---: | ---: | ---: | ---: |
| HAVEN | 98.3051% | 85% | 98.9309% | 95.0617% |
| RVProbe | 100% | 99.1667% | 99.3421% | 100% |

结果和费用并不代表独立功能正确性证明。历史 5 个完整结果加上本次 timer，当前完整清单为 6/16；后续动态状态见配对进度文件。

## 输出预算耗尽与后续批次上限

CAN round-2-repair-1 与 simple_spi batch-e 首轮均得到 provider `finish_reason=length`，最终 UT 输出字符数为 0，completion 用量分别 65,559 / 65,578 tokens（含短 bootstrap 调用）。这些不是编译错误，没有自动重复请求；已报告费用保留。CAN 先前接受的一轮数据仍是部分结果，不计为完整通过。

common-evidence-v5 将两侧每轮意图数明确上限为 4：HAVEN 一个 DSL sequence 对应一个 intent；RVProbe 一个 Gen label 对应一个 intent。超过上限在代码生成/编译前拒绝并进入原有 schema 修复预算；不截断模型答案，不删除选中意图的条件。最多 3 轮、每 intent 最多 4 个 witness 不变。新的 paired manifest 记录 `intent_batch_limit=4`；旧批次记录保持原样，不能把跨配置结果说成受控同版本实验。293 项回归测试通过（16 跳过）。

已向用户非阻塞确认：模型在固定预算内失败、而框架正常的情况，是否作为有效失败实验纳入最终配对统计，或继续只接受两侧通过。确认之前不把失败重标为 completed，未完成设计队列照常执行。

common-evidence-v6 修复共享上下文漏掉 BFM 配置的问题：原先仅提供调用签名、pin ownership 和时钟，未提供外部 RAM 深度/地址单位等实际配置。ethmac 生成的高地址 DMA 超出固定 Wishbone RAM，实测触发 MEMORY_ADDRESS。现在两侧同样得到冻结 blueprint 的 BFM 配置及已编译 BFM 的公开 interface parameter 声明；不扩大 RAM、不更改运行中的 BFM、不注入历史场景或 BFM 方法体。296 项回归通过（16 跳过）。旧运行保持原版本，不对单侧热更新上下文。

## 第二个新完成配对：ue_gpio

batch-a/ue_gpio 两侧各 3 轮，均正常到轮数预算。HAVEN 206,429 tokens / 431.965 秒；RVProbe 186,013 tokens / 868.998 秒。完整通过数达到 7/16。

| 方法 | Line | Condition | Toggle | Branch |
| --- | ---: | ---: | ---: | ---: |
| HAVEN | 98.6301% | 86.4% | 97.2% | 95.8333% |
| RVProbe | 100% | 95.2% | 85.9273% | 100% |

此设计 RVProbe token 更少、line/condition/branch 更高，但 toggle 低于 HAVEN；不合并为“全部指标更好”。

## 共享 item 输入域约束清理

CAN batch-f 的 HAVEN 随机化失败暴露旧 item 将 8 位地址限制为 0–31；RVProbe 原始 IO 没有同样的场景限制。离线审计同时发现 ETHMAC、UART、GPIO item 中存在 sequence-owned 输入场景约束。`item_contract.py` 按冻结接口映射定位并删除触及这些字段的 constraint 块，保留字段类型和不相关的结构约束；记录删除源码及前后哈希。不是在某侧追加 Assume，也不改生成的 UT。

四个新环境在 `out/experiments/fixed16-domain-refresh-20260910-v1` 编译通过，原有 baseline sequence SV 原样复用，没有模型调用。新冻结记录 `fixed16-environments-20260910-v4/stage1-map.json` 全部 prepare 通过。300 项测试中 284 通过、16 跳过。配对入口新增检查：旧环境仍包含此类约束则在付费调用前拒绝，必须通过新的共享环境重新验证双方。

batch-h 已启动 CAN → ETHMAC → UART → GPIO 的新配对。先前正在运行的进程不热修改，其失败及费用继续保留。旧队列里尚未启动、使用旧 item 的设计会被新检查拒绝，由 batch-h 接续。历史通过结果仍保留，但本次清理使环境版本不同，不能把所有历史结果描述为统一配置的论文最终队列。

16:04 UTC：batch-f/simple_spi 和 batch-g/spi 仍分别在 RVProbe 首轮/第三轮发生 provider 截断，输出 UT 字符数 0；本次对话报告总 tokens 分别 100,372 / 84,913。SPI 已接受 2 轮但整体 failed，simple_spi 为 0 接受轮。两者暂不再启动相同配置的随机重试，等待完成口径/响应预算方面的用户方向，其他设计仍执行。旧队列的 UART/ETHMAC 如预期在准备阶段被 legacy item 检查拒绝，由 batch-h 使用新共享环境接续。

汇总器为历史恢复结果增加 `token_scope=continuation_only` 和 † 标识：AXIL 的 HAVEN 0 / RVProbe 76,223 仅为续跑新增用量，不是两侧完整模型成本。不能用这组增量费用做方法效率比较。相关 2 项回归测试通过。

## 第三个新完成配对：ue_spi

16:08 UTC 确认 batch-a/ue_spi 双方各 3 轮正常完成，完整清单达到 8/16，UE_UART 自动启动。HAVEN 179,481 tokens / 340.194 秒；RVProbe 284,444 tokens / 1238.039 秒。当前全部回归为 302 项：286 通过，16 跳过。

| 方法 | Line | Condition | Toggle | Branch |
| --- | ---: | ---: | ---: | ---: |
| HAVEN | 99.1031% | 91.9831% | 76.1949% | 97.1631% |
| RVProbe | 99.5516% | 96.6245% | 67.0956% | 98.5816% |

16:10 UTC：I2C HAVEN 正常结束 2 轮；RVProbe 第一轮已接受，但第二轮 provider 截断，最终 UT 输出为空。该对话 104,478 tokens，完整尝试保留为 failed。不是编译或原生回放故障，不对同一配置自动无限重试。

## 编译错误反馈边界修复

16:39 UTC 左右审计 CAN 的真实语法失败发现：`parse_type_errors` 把 Scala parser 崩溃报告的 compiler settings、classpath、堆栈继续拼入上一条源码错误。现于显式崩溃报告起点结束该诊断，后续真正源码错误仍可单独提取。使用保存的完整 `solve/compile.log` 离线验证，仍为同一条 68:27 的 `unindent expected` 错误，message 从 9,897 字符减至 129；完整 compile.log 不变。不能使用 report.detail 的截断尾部代替完整日志测试解析。两项新增测试验证源码位置/类型详情保留、崩溃元数据分离及后续错误保留。已经发送的旧请求不热修改。

batch-h/CAN 最终 failed：HAVEN 接受 2 轮后 bus-off Poll 仍超时，共 989,907 tokens；RVProbe 接受 1 轮，第二轮修复经过语法诊断后又 provider 截断，共 676,473 tokens。最后一个截断对话为 192,651 tokens。已归档，不对同配置盲目重跑。ETHMAC 自动接续。最新完整回归：304 项，288 通过、16 跳过。

## 第四个新完成配对：ue_uart

16:42 UTC 确认 batch-a/ue_uart 双方各 3 轮完成，完整清单达到 9/16，SDRAM 自动接续。HAVEN 133,285 tokens / 276.705 秒；RVProbe 369,031 tokens / 1787.770 秒。RVProbe 第二轮因一个真实 LTL 回放失败进行修复后通过；第三轮 Ref/Node 累加类型错误也由模型预算内修复，未手工改写 UT。

| 方法 | Line | Condition | Toggle | Branch |
| --- | ---: | ---: | ---: | ---: |
| HAVEN | 96.7742% | 86.5497% | 67.4334% | 91.7431% |
| RVProbe | 97.8495% | 96.4912% | 66.1017% | 95.4128% |

17:10 UTC 左右：SDRAM 配对终止。HAVEN 三轮 completed，818,518 tokens；RVProbe 首轮 `read_request_acknowledged` 四条原生回放均未命中，修复响应被截断，最终 failed、0 接受轮，共 253,492 tokens。最后截断对话 149,930 tokens。batch-a 已全部终止，所有设计结果及失败费用归档；完整配对仍为 9/16。

ETHMAC 新环境没有再出现隐藏地址约束冲突或 RAM 越界，但 HAVEN 第二轮修复后仍失败（第一轮已接受）；RVProbe 第一轮通过，第二轮的 `slave_bd_write_then_read_data` 与 `tx_dma_read_at_programmed_pointer` 共 8 条原生回放未满足，继续预算内修复。

ETHMAC 随后终止，双方均 failed、各接受 1 轮。HAVEN 共 1,468,658 tokens，修复后 RX 错误场景仍 Poll 超时；RVProbe 共 786,898 tokens，修复后 6 条候选失败，仍涉及上述两个意图。其中一条额外触发 `memory_index` 的 MEMORY_ADDRESS 检查：原始日志明确显示地址 `00000000xxxxxxxX`，即未知值，不是已证实的 RAM 容量不足。不得靠扩大 RAM、将 X 置零或屏蔽输出让它通过。UART 自动接续。前文“没有 RAM 越界”仅描述此前阶段，不意味着后续没有 BFM 地址检查失败。

## 第五个新完成配对：UART

17:49 UTC 确认 batch-h/uart 完整配对。HAVEN 3 轮以 round_budget 结束，183,475 tokens / 486.582 秒；RVProbe 2 轮以 coverage_stalled 结束，155,388 tokens / 1311.959 秒。新冻结环境两侧一致，完整通过清单达到 10/16。最后一个新环境复测 GPIO 自动启动。

| 方法 | Line | Condition | Toggle | Branch |
| --- | ---: | ---: | ---: | ---: |
| HAVEN | 98.8095% | 89.7436% | 95.8537% | 98.2143% |
| RVProbe | 97.6190% | 94.8718% | 90.7317% | 96.4286% |

本次 RVProbe token 更少，但除 condition 外其他三项覆盖低于 HAVEN；不能描述为全面优于。全部失败重试仍在追踪清单里。

## 最后一个新环境复测：GPIO

18:17 UTC 确认 batch-h/gpio 完整完成。HAVEN 2 轮以 coverage_stalled 结束，165,782 tokens / 327.592 秒；RVProbe 3 轮以 round_budget 结束，290,928 tokens / 1211.363 秒。双方 line/branch 均 100%；HAVEN condition 85.4749%、toggle 74.4041%；RVProbe condition 100%、toggle 92.7355%。该结果替换动态汇总中的旧 GPIO 选中结果，但旧尝试及其费用仍保留。

全部本轮队列已终止，冻结环境哈希核对无变更。10 个完整通过：ALU、AES、SHA3、AXIL、UE_TIMER、UART、GPIO、UE_GPIO、UE_SPI、UE_UART。6 个仍未完整通过：CAN、ETHMAC、I2C、simple_spi、SPI、SDRAM。后四者最新 RVProbe 尝试因 provider 截断终止；CAN 两侧分别存在 Poll 超时及截断；ETHMAC 两侧分别存在 Poll 超时及原生 LTL/未知 DMA 地址拒绝。没有把这些重标 completed，也没有无限重试同配置。

当前追踪清单累计 15,849,843 tokens，范围仅限清单列出的历史/续跑尝试，不含未列入的旧尝试或全部 Stage-1 成本；AXIL 恢复费用仍仅为增量。不能作为同版本统一实验队列总成本或方法胜负结论。

最终归档核对另写 `out/experiments/fixed16-archive-audit-20260910.json`。batch-b 的 ETHMAC 曾记录 JG session/db 在复制时“目录已存在”，历史 archive_error 保留；后续哈希核对与目录类型核对独立记录，不抹掉原错误。归档验证器补充目录符号链接与空目录检查，并显式关闭哈希文件句柄。

目标仍未标完成。待用户确认：是否将固定预算内真实失败作为有效实验纳入配对结果，或授权统一调整预算/策略后另开新批次。不能只挑成功重跑来制造 16/16。

最终归档核对完成：本轮全部 20 份配对 summary 对应目录的非缓存文件、目录和符号链接均验证通过，0 个失败；原始 archive_error 不被覆盖。审计、追踪清单及汇总副本分别保存在持久归档根的 `final-archive-audit.json`、`final-tracking.json`、`final-paired-progress.json`。汇总器增加 `archive_source`，易失目录不存在时自动使用持久归档，测试覆盖这一情况。

## 2026-09-11：ETHMAC 冻结目标离线编码/回放验证

独立四态编码原型首次为 `bd_word_write_readback_value` 找到一条通过原始 RTL、
原始 Cover 的轨迹；零 DeepSeek 调用。破坏写入数据后，原始 Cover 正确拒绝。
完整框架回归 321 通过、13 跳过。详见 [JG 接口与编码验证](jg-xprop-settings-20260911.md)。
原型尚未接入正式后端，没有新增正式配对；当前完整配对仍为后续追踪清单中的 11/16。
完整证据及 `result.json` 位于
`/var/storage/workspaces/clo91eaf/rvprobe-xprop-bridge-20260911-v1/`。
