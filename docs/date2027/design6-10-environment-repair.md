# 设计 6–10：共享环境修复与验证

2026-09-09。这里记录框架修复，不是新一轮配对覆盖率结果。
本次未调用模型；旧的 150,049 tokens 失败准备成本仍保留在原批次，未覆盖或重新计为零。

## 已实现的修复

- CIRCT 展开原 RTL 后提取端口方向和宽度；旧 `.v` 使用 Verilog-2005，补充默认 timescale，不改 RTL。
- JG 从展开的设计报告时钟/复位角色。I2C 的低有效 `arst_i` 自动进入环境；内部 FIFO clear 条件留在 RTL 中，不变成 Assume。
- 多时钟逐事件导出/回放：保留副时钟边沿，按声明比例验证 VCD，补全最终 cover 采样沿；不把不同的 RTL 时钟绑成一个。
- BFM 端口别名、位宽和驱动归属检查。时钟、复位、静态输入、BFM 引脚不再分配给主动 driver；非法写入明确报错。
- I2C 的 split pad 保持原 IO 方向，通过 `tri1`、输出使能和开漏 BFM 接线；逻辑 1 是释放 SDA，不是主动拉高。
- GPIO 的输出使能回接关系进入共同环境元数据，同时约束形式输入和回放输入。
- 多主动 agent 使用同一 sequence 事务的副本并行驱动各自引脚；主 driver 等双方完成。不新增代理、不重复执行 BFM action、不静默闲置副 driver。
- 共享 DUT 输入 mux 让 monitor 观察最终 DUT 引脚。旧的单周期回放拒绝接收新事件格式；求解检查点必须匹配时钟、复位和基础设施约束。
- 两侧 prompt 获得同一份环境和驱动归属元数据。未向 skill/RAG 增加设计答案或历史 UT。
- HAVEN 的修复已沉淀到 `experiments/patches/haven-axi-transaction-contract.patch`，附 19 个文件的前后哈希。
- `flake.nix` 管理 Python/uv 和 venv 所需的 C++/zlib 动态库；`experiments/haven-python` 不再引用临时 launch.sh 或固定 Nix store 哈希。

## 已完成的验证

| 检查 | 结果 | 本地证据目录（仓库根目录下） |
| --- | --- | --- |
| UART：原 RTL + 规范化环境 | VCS 编译通过；旧 Phase-2B 未修改 | `out/diagnostics/uart-environment-v2` |
| CAN：原双时钟 RTL + 独立时钟 | CIRCT/JG 预检、VCS 编译通过 | `out/diagnostics/can-environment-v3` |
| Ethernet：原三时钟 RTL | CIRCT/JG 预检、VCS 编译通过；本项没有生成 PHY BFM | `out/diagnostics/ethmac-environment-v3` |
| I2C：原 RTL + 开漏 BFM + 双复位 | VCS 编译通过 | `out/diagnostics/i2c-environment-v3` |
| GPIO：31 位原 RTL + GPIO BFM | VCS 编译通过、回接元数据导出通过 | `out/diagnostics/gpio-environment-v2` |
| 新 UT → JG → 4 条去重 witness → 共享 UVM | 204 个事件、128 次已知输出检查；原 RTL cover 命中 | `out/diagnostics/shared-event-flake-four-v2` |
| JG factor=1 边界情况 | 共享 UVM 回放与 cover 检查通过 | `out/diagnostics/shared-event-factor1-v1` |
| 多 driver 分发 | 10 个事务不丢字段、并行完成、无死锁 | `out/diagnostics/agent-dispatch-flake-v2` |

双时钟 UT/RTL 是明确标记的合成回归夹具，不进入 benchmark prompt、skill 或 RAG。

框架 Python 回归在 Nix 环境下为 193 项：177 通过、16 项跳过；HAVEN 为 157 通过、1 项跳过。
`mill experiments.compile` 与 `git diff --check` 通过，外部补丁 19 个安装哈希全部匹配，反向 dry-run 检查通过。

## 重要边界与剩余验收

物理环境编译通过不等于五个真实设计的完整 Stage-1、配对闭环已经通过。
UART/I2C 仍需用修复后的 blueprint 重新生成受影响的组件；CAN/Ethernet/GPIO 的模型生成组件仍待生成与运行验收。
因此当前不能报告新的 HAVEN/RVProbe 覆盖率或 token 优劣。

事件回放使用 sequence 控制的原始引脚通道。普通 sequence 保留 native agent/BFM；raw sequence 接管 DUT 输入，仍强制电气、静态和复位关系。
这**不是**把 BFM 的整个自主协议行为导入 JG，也不是两种激励后端表达能力完全相同的证明。
新 bundle 明确记录 `bfm_behavior_formally_modelled: false`；应作为共享事件环境变体报告，不能冒充未经修改的原版 HAVEN 条件。
如果实验要求逐行为复用 native BFM，尚需 BFM 形式建模/合法序列转换，不能通过删除 BFM 或忽略回放失败来宣告完成。

## 复现

先在自己的 HAVEN checkout 应用补丁、准备其 `.venv`，使用同一份原始 RTL。
商业工具与许可证仍由 `experiments/eda-shell` 提供；此脚本可替换为本机包装器。

```sh
python3 experiments/check_haven_component_patch.py /path/to/haven
nix develop -c experiments/haven-python /path/to/haven \
  experiments/smoke_event_transport.py --haven-root /path/to/haven \
  --out out/diagnostics/my-event-check --solve --samples 4
nix develop -c experiments/haven-python /path/to/haven \
  experiments/smoke_agent_dispatch.py --haven-root /path/to/haven \
  --out out/diagnostics/my-dispatch-check
```

输出目录必须是新的；这些命令没有模型调用。
`check_saved_environment.py --stage1 ...` 在新目录复核已有 Phase-2B；
`haven_stage1_batch.py` 已在 Phase-3 之前接入环境预检，不再使用旧诊断脚本中“一律拒绝多时钟/BFM”的临时 gate。
