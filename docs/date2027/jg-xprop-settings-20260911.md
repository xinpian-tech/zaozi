# JasperGold XPROP 设置核对（2026-09-11）

本轮只查阅本机 JG 2021.03 手册并运行独立工具测试，不调用模型，不改实验 LTL、
DUT、Stage-1 或正式求解参数。不能将测试结论外推为所有版本的 JG 都不支持。

## 原生能力与作用范围

`/opt/cadence/JASPER2103/doc/jaspergold_xprop_userguide.pdf` 第 16–18、45–46 页，
以及 `jaspergold_command_reference.pdf` 第 179–181、199–200、296–297 页说明：

- XPROP 原生追踪不确定值的传播，包括悬空总线和未初始化寄存器。
- `set_xprop_use_bus_floating on` 将悬空总线纳入 XPROP 来源；
  `set_xprop_use_reset_state` 控制未初始化状态来源。
- 可用 `assert -xprop` 或受支持的 `$isunknown` assertion 检查传播。
- **`assume -xprop` 仅作用于 XPROP、SPV 和 `cover -path`，普通 FPV 属性忽略它。**
- `cover -path` 检查信号间的信息传播路径，并不是任意 SVA Cover 的替代品。

所以“JG 完全不会跟踪寄存器中的不确定值”不准确。已确认的缺口在于当前普通
FPV Cover 生成路径没有获得这种语义，而非工具所有应用都没有该能力。

## 最小电路实测

电路只有已复位的输出寄存器，采入 `enable ? data : Z`，避免未初始化 RAM
混淆实验。三组运行保持相同源码与 reset，`set_xprop_use_reset_state=on`。

| 配置 | 原生“输出始终已知”assertion | 普通“禁用后一拍为全 1”Cover | 含 `$isunknown` 的 Cover |
|---|---|---|---|
| bus floating off | proven | covered | unprocessed |
| bus floating on | cex | covered | unprocessed |
| bus floating on + assume -xprop 输出已知 | proven | covered | unprocessed |

两个原生 assertion 接口（`assert -xprop` 与嵌入式 `!$isunknown`）结果一致。
第三行的 proven 来自新增假设，不能解释为电路已修复；普通 Cover 不受该假设约束。
含 `$isunknown` 的 Cover 报告 WNL038/WPM022，被禁用，并没有成功生成已知值 witness。

另外测试了将目标取反放入 assertion，并嵌入 `!$isunknown` 的表达方式；
两个属性同样因未支持的使用形式而被禁用。不能仅把 Cover 改成 Assert 就宣称修通。
将时序目标作为 implication 前件、`$isunknown` 作为后件的另一种写法也得到
WNL038/WPM022 和 unprocessed，保存在 `implication-probe.*`。

## 证据

归档：`/var/storage/workspaces/clo91eaf/rvprobe-jg-xprop-review-20260911-v1/`。
`probe.sv` / `probe.tcl` / `probe.log` 保存三组配置和全部状态；
`negated-probe.*` 保存取反 assertion 的测试。
这只是工具能力诊断，不增加 11/16 的正式完整配对数。

## 后续：原生接口桥接测试

归档：`/var/storage/workspaces/clo91eaf/rvprobe-xprop-bridge-20260911-v1/`。

- `get_property_info <cover> -list expression` 可获得 Cover 的内部匹配信号。
  对这个信号执行 `assert -xprop` 成功，且能发现最小负例的不确定性。
  **能检测不确定性，不等于能求出确定为真的原始 Cover witness。**
- Tcl 中正向 `$isunknown(...)`、条件正向 `$isunknown(...)` 虽然被接受，
  但属性信息显示为普通 `assert / fpv`，不是 `assert(xprop) / xprop`。
  不能以“命令没有报错”判断已启用原生四态检查。
- `check_xprop -export` 实际导出部分加密的仿真检查器；本次未获得可连接到
  普通 Cover 的确定性信号。没有解密或修改受保护内容。
- 测试了 `cover -path` 加 `assume -xprop`，以及辅助 X 注入加原生
  `assert -xprop`。两种方案都仍然接受了“前一拍采入 Z、后一拍要求全 1”
  的负例。将时序匹配换成普通组合 wire 的控制实验也没有消除假轨迹。
  因此这些组合没有接入正式后端。

`path-probe.*`、`inject-probe.*`、`wire-probe.*` 保留脚本、日志、波形和失败尝试。
以上是本机版本和本次配置的实测，不是对所有 JG 版本或全部接口的不可实现性证明。

## 后续：独立编码可行性测试

后续多设计诊断发现还必须显式传递编码初值并采用 `proc -ifx`。
早期的单设计通过不构成编码正确性的充分证据；新的正反例和续跑记录见
[剩余配对与框架修复](remaining-pairs-20260911.md)。正式后端仍未启用该原型。

尝试使用 Yosys 0.67 自带 `xprop` pass 编码数值与不确定掩码，再由 JG 求解，
没有手写完整的 RTL 四态传播器。工具来自当前 flake 的
`legacyPackages.x86_64-linux.yosys`，尚未加入正式实验默认依赖。

最小电路的 `encoded-probe-v4.*` 结果为：

| 检查 | 结果 |
|---|---|
| Z 采入后的全 1 目标 | JG unreachable |
| 有效数据采入后的全 1 目标 | JG covered |
| 原始四态 RTL 与编码电路的数值、掩码、原子真值比较 | VCS 64 次采样通过 |
| 负例 / 正例真实命中 | 0 / 12 |

这不是任意 RTL 的等价性证明。当前原型使用 `async2sync`，不能假定它保持任意
异步复位脉冲或边沿重合的行为；Z 到 X 的归一化也不能无条件用于多驱动解析。
`-required -formal` 的通过不能代替这些语义检查。测试限制写入每次 `identity.json`。

ETHMAC 直接读取冻结的原始 Cover，仅将序列中的布尔原子转换成“值为真且掩码为零”，
没有修改原始 LTL 文件、DUT 或固定 Stage-1。`ethmac-encoded-v2` 的第一条候选
在原始 RTL 中仍被拒绝：波形显示它利用了未驱动的 `dbg_dat`；初版编码尚未将
内部未驱动信号列为不确定来源。后续版本增加 `setundef -undriven -undef` 重测。
所有候选最终仍由 `ltl_replay.attach` 加载原始 Cover，在原始四态 RTL 上决定是否接纳。
这些记录均为零模型调用的诊断，不纳入正式配对指标。

### ETHMAC：首次通过冻结目标的原生回放

`ethmac-encoded-v4` 补齐未驱动信号处理后，JG 找到长度 6 的候选；
`native-artifacts/ethmac-replay-v4/summary.json` 记录原始 RTL 回放 **passed**。
原始 Cover `bd_word_write_readback_value` 为 **37 attempts, 1 match**，
输入/复位/事件回放为 `RVPROBE_REPLAY_PASS 35`。原生执行及覆盖率提取共 12.269 秒。
这里的长度 6 是导入器记录的 witness 长度，不用于替代实际事件数或仿真时间。

负向测试仅将候选的 `wb_dat_i` 驱动改为 0，不改原始 LTL 或 DUT：
`native-artifacts/ethmac-replay-v4-negative/summary.json` 为
`rejected_as_expected`，失败类型是 `formal_replay_semantics_mismatch`，耗时 12.473 秒。
这验证了真正的原始 Cover 检查仍在生效，而不是只靠共享 testbench 的 PASS。

完整框架回归：334 项，321 通过、13 跳过；见 `framework-tests.log`。
正式完整配对仍为 **11/16**，本次没有调用 DeepSeek，也没有新增正式配对。

状态边界：**独立修复原型已取得一条有效 ETHMAC witness，尚未接入正式求解/采样后端。**
还需要处理或明确限制异步复位、多驱动和更广的 LTL 语法，建立独立的来源记录，
再做更多设计回归。不能将原型的不可达结果作为原始 DUT 目标不可达的证明。

复现入口为归档中的 `try_encoded_goal.py` 和 `replay_encoded_goal.py`；每个
`ethmac-encoded-*` 子目录保留原子表达式、原始 job/goal 指纹、生成的 `.ys`、
求解 `.tcl`、变换模型和日志。原始源文件由 `frozen_inputs` 校验，回放通过
`ltl_replay.attach` 重新挂接冻结 Cover。早期候选 JSON 中继承的 `ms` / `startedUtc`
是原始求解的元数据，**不是本次编码求解耗时**；本次回放时间读取外层 `elapsed_seconds`。

一次附加内部信号导出运行在 NFS 的 JG SQLite 路径触发 SIGBUS，记录保留在
`ethmac-encoded-v3`；有效 v4 使用本地 `/tmp` 的 JG 项目目录，结果归档到指定存储。
