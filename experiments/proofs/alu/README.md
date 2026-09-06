# ALU 独立可达性检查

这里保存针对原始 `alu_top.v` 第 336 / 401 行路径条件的人工属性，不实现 DUT，
不参与 LLM 生成，不进入 RAG，也不被覆盖闭环自动当作排除依据。

在仓库根目录、配置好 JG 及许可证后，用新的项目目录运行：

```sh
experiments/eda-shell -c \
  'jg -batch -tcl experiments/proofs/alu/prove_deadcode.tcl -proj out/experiments/my-alu-deadcode-jg'
```

检查每个属性的 `JGSTATUS`；`unreachable` 与超时 / 未确定必须区分。
该人工检查与模型返回的 `proofObligations` 是两种独立产物；后者只是待证元数据。
历史结果见 [DATE 总览](../../../docs/date2027/README.md)，当前生成 / 回放命令见 [实验说明](../../README.md)。
