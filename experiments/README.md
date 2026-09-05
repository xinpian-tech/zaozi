# rvprobe：外部 RTL → 动态 UT → sequence

当前流程只做验证和 sequence 生成，不在 zaozi 中复刻 benchmark DUT。

```text
原始 RTL + IO manifest ──────────→ 一个共享的 VerilogWrapper
当前 RTL / 覆盖残余 + 框架 RAG ──→ LLM 的 Sem 意图 JSON
                                      ↓
                           每条意图自动生成一个 UT
                                      ↓
                           scalac → JasperGold witness
                                      ↓
                           ABI → stimulus → UVM sequence
                                      ↓
                           现有 driver 回放 → VCS / URG
```

## 代码归属

- `stdlib/`：恢复为 `ut` 基线内容，不新增实验模块或测试。
- `sequence_framework.py`：设计无关的 IO binding、UT 和 runner 生成器。
- `designs/alu.json`：原始 RTL 路径、端口、时钟 / 复位、sequence item 类型及协议说明，没有用例答案。
- `fixtures/haven/`：迁移过来的第三方 RTL，内容未改。
- `src/`：固定入口和通用 RAG 示例，没有设计专用 UT 或共享 Generated.scala。
- `legacy/`：历史人工 UT、旧 driver 和回归，独立 Mill 模块，不进入活动实验的 classpath 或 RAG。
- `<run>/sources/` 或 `<run>/attempt-N/sources/`：本轮生成的 `DesignBinding.scala` 和 `Generated.scala`。

一个设计只维护一份接口定义，同一轮所有意图共用一个 wrapper。UT 数量取决于本轮意图数，
不是预先为每种场景维护一份 UT 源文件。
用户 label 用于结果归档；SV 属性使用独立的 `rvprobe_generated_N` 名称，避免与表达式里的局部寄存器重名。

## 无模型复现

在仓库根目录运行；每次换一个新的输出目录。这个独立 synthetic RTL 只是通用框架的回归 fixture，
不是 HAVEN 实现，也不在 RAG 来源白名单中。

```sh
nix develop . -c python3 experiments/sequence_framework.py \
  --design experiments/tests/fixtures/tiny_design.json \
  --response-file experiments/tests/fixtures/tiny_intents.json \
  --out out/experiments/my-runtime-check --check-io

nix develop . -c python3 experiments/ut_harness.py \
  out/experiments/my-runtime-check/sources \
  --out out/experiments/my-runtime-check/solve --compile-only

nix develop . -c env ZAOZI_EDA_SHELL="$PWD/experiments/haven_tb/eda-shell" \
  python3 experiments/ut_harness.py out/experiments/my-runtime-check/sources \
  --out out/experiments/my-runtime-check/solve
```

前两步只需要 Nix 开发环境，不需要 LLM 或商业 EDA。最后一步需要 JasperGold 和许可证；
`eda-shell` 针对原主机，迁移时配置 `SNPS_FHS_ENV` 或换成自己的 wrapper。
四条保存的意图分别测试 value / state / temporal / relation，不进行在线模型调用。

生成文件和输入哈希在 `sources/`；输出包括 `solve/report.json`、每条意图的 ABI、lowered SV、
JG 日志、witness VCD、`stimulus.json`、独立 sequence，以及一个串接 sequence。
`generated`、`infeasible`、`unknown` 分开记录；compile-only 通过不代表已求解。
后端明确选择本轮生成属性，不把原始 RTL 中其他 cover 的 witness 当作本轮结果。

## 接入设计与 IO

参照 `designs/alu.json` 填写实际 RTL 文件和端口，路径相对 manifest 所在目录。
支持平铺 input / output、bits / bool / sint、整数参数、一组时钟和复位、至多一个 include 根目录。
时钟角色和复位极性须显式声明，不能仅由 Verilog 位宽推断。
`--check-io` 调用 CIRCT 的 `circt-verilog --ir-hw`，核对 elaborated top 的端口名、方向和位宽，
不使用正则解析 Verilog 源码。这个可选 preflight 当前不支持参数覆盖；参数由后端 elaboration 检查。
尚未实现自动推断协议、多时钟、inout 或任意结构化端口，也没有声称旧的所有设计已完成迁移。

JasperGold 使用原始 RTL 和 wrapper 的 lowered SV；zaozi 只新增接线、验证谓词及历史监测寄存器，
不生成 DUT 的替代实现。`--rtl` 同时替换 prompt 和求解输入。求解前核对 RTL 及 include 文件哈希，
不覆盖已有结果报告。

当前 coverage prompt 的源窗口取 manifest 的第一个 RTL 文件。多文件设计须让残余对应这个文件，
或补充显式的残余文件映射后再做实验；端口定义不自动完成多文件覆盖目标定位。

## 模型与回放

`sequence_experiment.py` 接收 `--design`、`--modinfo`、`--out`；旧名字 `alu_residual_loop.py` 仍可用。
LLM 返回 JSON，其中每个 `expression` 是真正参与编译的 `Sem.Intent`，不是预写 UT 的参数。
契约及 framework-only RAG 边界见 [PROMPT](PROMPT.md)，ALU 消融见 [运行说明](haven_tb/alu/README.md)。
保存的 `--response-file`、`--prompt-only`、本地检索均不需要模型凭据。

UVM item 的字段必须与 ABI drive ports 对齐。导出器写出每个 beat 的确定值，不负责 driver、
握手或 witness 之间的 reset。独立 witness 串接后不自动保持初态或时序性质；
必须检查协议映射并通过回放确认，必要时逐 intent 单独 reset / 回放。
完整 HAVEN 覆盖复现还需要未纳入 Git 的 bench / 残余输入，不能凭导出成功声称覆盖闭合。
模型 Scala 不是受限语言，类型检查也不是安全沙箱：不可信输出需隔离或审查后执行。

## 回归

```sh
python3 -m unittest discover -s experiments -p 'test_*.py' -v
nix develop . -c mill --no-server experiments.tests.testForked
nix develop . -c mill --no-server utlib.tests.testForked
nix develop . -c mill --no-server experiments.legacy.tests.compile

RVPROBE_RUN_TOOL_TESTS=1 RVPROBE_RUN_JG_TESTS=1 \
  python3 -m unittest discover -s experiments -p 'test_sequence_framework.py' -v
```

最后一组实际检查 CIRCT IO、正确 / 错误端口和错误类型的编译、四类意图求解及导出。
只测 CIRCT / scalac 时省略 `RVPROBE_RUN_JG_TESTS=1`。工具测试产物保存在
`out/experiments/runtime-ut-regression-*/`。没有任何测试向模型服务发请求。

旧结果和污染审计保持原样，参见 [DATE 总览](../docs/date2027/README.md)；
它们不代表 `runtime-ut-v1` 的在线模型或 RAG 效果。
