# 共享环境与 sequence 验收

两侧共享 RTL、复位/时钟、电气连接、外部设备模型、仿真错误判定及覆盖统计。
激励的生成方式不要求相同：HAVEN 使用事务/任务，RVProbe 使用 LTL 生成逐事件 sequence。

## 环境角色

- `stimulus`：允许 sequence 选择其驱动波形。当前 UART 模板属于这一类。
- `stimulus-with-electrical-feedback`：GPIO 外部输入可控，但输出使能反馈不能被绕过。
- `response`：例如 I2C/SPI 从机、存储器。保留实际响应驱动器；形式端当前使用过近似，不声称已经形式化完整 BFM。
- `stimulus-with-clocked-loopback`：MII PHY 的时钟、延迟环回、外部发帧分开适配。DUT 与 PHY 使用同一时钟，显式 RX 激励通过 PHY 仲裁；没有 RX 激励时仍保留环回，col/crs 保持真实响应。

回放时外部模型接收与 DUT 相同的有效时钟/复位，响应引脚不接入任意波形驱动。每个事件前核对实际响应和 witness；不一致即失败，不能纳入覆盖率成功结果。
复位前缀不要求外部响应等于任意 idle 填充值；所有 witness 事件仍严格检查。
形式采样点要求实际输入严格等于 witness。补充的非形式时钟事件继续保持外部激励；GPIO 实际管脚则按同一输出使能/输出值解析，并严格检查解析后的电平。不会忽略半拍检查，也不会将响应设备的输出改成任意激励。
这是抽象候选的具体执行验收，不是对整个环境模型的等价性证明。过近似可能产生更多被拒绝的候选，相关 token/时间与失败必须保留。

被动开漏引脚由 pin-ownership 自动识别：仅 DUT 输出与上拉驱动时，形式约束为 `input == (enable_n | output)`；有从机参与驱动时保留响应过近似。补充事件按实时电气解析检查，形式采样仍精确比较 witness。它不是人工编写的场景 Assume。

## 通用无效激励诊断

事务字段存在不代表该字段能驱动物理输入。对于环境/BFM 持有的输入，`randomize_send` 中的字段约束可能被总线驱动器完全忽略。
这类约束须在生成阶段报错，显式外部输入应使用已安装 BFM 的实际任务 API。
GPIO 模板提供通用 `set_input(value)` 和 `resume_patterns()`，不含任何设计专用值、寄存器地址、验证意图或覆盖答案。
修复序列必须依据原始规格、实际接口和测得的失败日志，不能删除检查、改为恒真或改错误等级来掩盖失败。
每条序列需要建立其依赖的配置和外部输入状态，不能假定前一条序列恰好留下所需配置。事件配对入口统一采用 `fresh-process-per-sequence-v2`：包括初始基线在内，每条序列在新进程执行，再用 URG 合并覆盖率；不删除任何基线测试或检查。这改变了旧版“基线共用进程、新增序列独立进程”的不一致规则，必须与旧结果分开报告。
若规格文字与原始 RTL 存在差异，应明确诊断，不把不存在的硬件行为写成轮询条件。

所有基线修复写入新目录；通过编译不等于通过仿真，也不等于完成配对实验。

## 实测记录（2026-09-09–10 UTC）

- `out/experiments/response-conformance-smoke-v2`：真实 VCS/UVM 仿真通过；51 个事件、102 次已知输出检查，设备看到相同有效时钟/复位；另一个故意矛盾的响应 witness 被 `ENVIRONMENT_WITNESS` 拒绝。合成夹具，不是 benchmark 结果。
- `out/experiments/stimulus-event-regression-v1`：原多时钟激励通路回归通过，原 RTL cover 命中。
- Python 回归：实验框架 207 项（189 passed、18 skipped），HAVEN 172 passed、1 skipped。
- `out/experiments/reactive-agent-conformance-smoke-v3`：反应式 UVM driver 的真实 VCS 回归通过，检查有效时钟/复位、保留响应、拒绝矛盾 witness。此前 v2 暴露虚接口实例声明晚于 config_db 使用的问题，现已修正；尚未以成功的 Ethernet 配对结果验证。
- `out/experiments/design10-gpio-shared-api-v1`：模型依据实际 API 修正 9 条基线，1 次调用、24,039 tokens、82.34 秒，一次编译通过；未手写 sequence。随后配对入口基线仍有两处超时，0 个 Stage-2 模型调用、7.91 秒。
- `out/experiments/design10-gpio-reset-guard-v1`：同一份模型 DSL 不变，仅重新渲染修正后的共享 GPIO BFM。输入控制任务现在等待复位释放，0 个新模型调用。
- `out/experiments/design10-gpio-reset-guard-diagnostic-v1`：输入同步 sequence 7 单独运行已通过；中断 sequence 4 仍失败。完整基线在 `design10-gpio-conformance-paired-v2` 中仍失败，不能拿单序列通过冒充完整配对成功。
- `out/experiments/design10-gpio-waveform-diagnostic-v4` 的只读端口/BFM 状态日志确认了复位期间调用被清掉的问题。VCD 在本机输出不完整，诊断依据为保存的 `DIAGNOSTIC_IO` 采样日志，不声称完成全波形等价验证。
- `out/experiments/design10-gpio-measured-baseline-v1`：模型基线修复结束，1 次调用、57,341 tokens、195.82 秒，9 条序列一次编译通过。模型将输入测试改为检测模式变化，不再声称验证给定输入值的逐值一致性。旧批量运行仍有中断超时，记录在 `design10-gpio-conformance-paired-v3`。
- `out/experiments/design10-gpio-conformance-isolated-v1`：采用 v2 独立执行策略后，全部 9 条基线通过。合并顶层覆盖率：line 100%、cond 60.89%、toggle 69.64%、branch 74.07%，综合 76.15。注意根目录 `execution-policy-correction.json`：运行时隔离实现已是 v2，但启动时 manifest 的旧文字标签尚未更新；保留原 manifest 与代码哈希，并明确记录实际策略，未修改测试、结果或费用。
- 同轮 HAVEN 第 1 轮返回 4 条 sequence，64,804 tokens、模型请求 257.38 秒；第一条尝试非法地址，被 item 的硬地址枚举约束阻止，VCS 报 `CNST-CIF`。虽然 UVM 汇总计数为 0，框架仍正确拒绝了该失败仿真；不能把它计为覆盖率成功。
- 同轮 RVProbe 经一次自动编译修复生成 6 个 intent、24 条 sequence，累计 166,738 tokens。回放在输出使能变化后的补充下降沿误将已解析 GPIO 管脚与原始外部驱动比较，导致失败。原始配对运行已结束：两侧均无接受的新增轮次，总计 231,542 tokens、1,234.34 秒；不能作为完成的有效配对比较。
- 两次新增共享基线模型修复合计 81,380 tokens；连同本轮两侧生成共 312,922 tokens。保留失败费用；未包含此前历史实验费用，不把复用或诊断报告为新模型实验。
- `out/experiments/design10-gpio-saved-oe-replay-v1`：修正补充事件的电气解析检查后，原先失败的同一条 sequence 在 VCS 中通过，8.60 秒，0 个新模型 token。形式采样点仍精确校验；新增回归覆盖未知输出使能、未知受驱动输出、普通输入不匹配及保留响应输入不能豁免。
- `out/experiments/design10-gpio-saved-all-replay-v1`：2026-09-10 00:08:22–00:13:23 UTC（UTC+8 为 08:08:22–08:13:23），完整重放 9 条共享基线和全部 24 条已保存 RVProbe sequence，33/33 通过。共 680 个回放事件，其中 200 个 witness 事件；300.68 秒，0 个新模型 token。覆盖数据库由 URG 实际合并，结果如下。此项只证明原候选在修复后的框架中通过，不代表 HAVEN 已修好或新闭环实验完成。

| GPIO 顶层指标 | 共享基线 | 基线 + 保存的 RVProbe 候选 |
| --- | ---: | ---: |
| 行覆盖 | 100.00% | 100.00% |
| 条件覆盖 | 60.89% | 77.09% |
| 翻转覆盖 | 69.64% | 91.66% |
| 分支覆盖 | 74.07% | 82.41% |
| 综合分数 | 76.15 | 87.79 |

上述是前一批结果；2026-09-10 04:00 UTC 起的新尝试见 [五设计结果](design6-10-complete-pairs.md)。新批次补上实际 item 声明的共同提示信息；共享 Wishbone 驱动按 ACK/ERR/RTY 终止，真正无响应仍报错。每侧每覆盖轮最多一次基于实际失败日志的候选修复，预算相同；失败候选不进入后续已接受序列或覆盖率。失败成本保留。

原实验目录不覆盖；共享 BFM 更新、DSL 复用和失败重试分别记录来源及费用。外部 HAVEN 修改已同步到 32 文件可移植补丁，并通过原文件哈希、正向与反向应用检查。

新增通用组件回归（均为零模型调用，不是 benchmark 答案）：

- `i2c-bfm-protocol-20260910-v1`：第九拍 ACK 保持、数据首位、连续读 ACK/NACK、寄存器指针、重复 START、错误地址与总线释放。
- `wishbone-driver-protocol-20260910-v1`：ACK/ERR/RTY 结束；无响应仍触发超时错误。
- `mii-bfm-protocol-20260910-v2`：独立发帧、环回与显式 RX 仲裁。
- `mii-transport-20260910-v2`：真实 VCS 检查原生时钟与事件时钟模式，PHY/DUT 同步，无原生振荡器泄漏，显式输入不绕过 PHY，无输入时保留环回。
- `wishbone-address-range-20260910-v1`：省略低地址位的物理端口按已记录的 RTL 范围映射；逻辑地址不能在转换前截断。ACK/ERR/RTY 与真正超时同时回归。
- 本次实验框架回归 219 项：201 通过、18 跳过。HAVEN 相关组件现有回归 38 项通过；这不等于重新执行了 HAVEN 全部测试。

RVProbe 工具握手仍只允许 `read_skill({})`；参数错误最多返回一次工具错误要求重试，不读取错误参数指定的文件，不提前发送设计。截断生成不自动补发。SDK 在输出上限异常中附带的真实 usage 也纳入费用记录，不能记成 0。

## 无模型调用复现已保存的候选

`experiments/replay_saved_candidate.py` 直接读取保存的 `candidate.json`，不重新生成 UT、求解或修改 sequence。输出目录必须全新；记录输入、框架哈希、开始/结束时间及零新增 token。默认重新执行所有基线和新增序列并合并覆盖；`--sequence N` 仅做第 N 条新增序列诊断，不声称完整基线验收。

```sh
nix develop -c experiments/haven-python /path/to/haven \
  experiments/replay_saved_candidate.py \
  --haven-root /path/to/haven \
  --bundle out/experiments/design10-gpio-conformance-isolated-v1/shared/bundle.json \
  --candidate out/experiments/design10-gpio-conformance-isolated-v1/paired/rvprobe/round-1/candidate.json \
  --out out/experiments/my-new-saved-candidate-replay \
  --eda-config experiments/designs/haven_eda.json \
  --eda-shell experiments/eda-shell
```

这种回放是框架修复的诊断证据，不能覆盖原始失败记录，也不能冒充重新完成了 HAVEN 与 RVProbe 的闭环配对。
