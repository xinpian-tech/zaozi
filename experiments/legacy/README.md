# 历史手写 UT 与回归（非活动 LLM 流程）

这里保留重构前的 `Haven*UT`、教学 UT、driver 和 2×2 实验脚本，便于审计旧结果。
这些文件不属于 stdlib，也不进入当前 LLM 运行器的 classpath 或 RAG 来源白名单。
为兼容已保存的 Scala response，旧 `package me.jiuyang.stdlib` 名字只在这个隔离模块中保留。

历史版本锚点为重构前 `507217bf04380dbd32a3af360e880bb5f8f01774`。
旧结果文档及 `docs/date2027/data/` 的原始 prompt / response 没有改写成新实验。

| 历史路径 | 当前位置 |
|---|---|
| stdlib/src/Haven*UT.scala 及本分支新增教学 UT | experiments/legacy/src/ |
| 本分支新增 stdlib/tests/src/ 测试 | experiments/legacy/tests/src/ |
| stdlib/tests/resources/haven/ | experiments/fixtures/haven/ |
| experiments/*FlowDriver.scala、AblationDriver.scala、src/Generated.scala | experiments/legacy/drivers/ |
| experiments/ablation.py | experiments/legacy/ablation.py |

编译历史回归：

```sh
nix develop . -c mill --no-server experiments.legacy.tests.compile
```

显式运行完整的历史 Scala response（不是旧两段列表）：

```sh
nix develop . -c env ZAOZI_EDA_SHELL="$PWD/experiments/haven_tb/eda-shell" \
  python3 experiments/ut_harness.py docs/date2027/data/alu-jg-round1-response.scala \
  --legacy --out out/experiments/my-legacy-replay
```

`--legacy` 将输入复制到新输出目录，仅替换迁移的 `stdlib/tests/resources/` 路径前缀，
保存原始哈希及变换记录，不修改原始 response。其他旧主机绝对路径、版本和 bench 仍需匹配。
可加 `--compile-only` 检查兼容性；输出目录不得重复使用。

历史 driver 并不都遵循同一报告 schema，命令、固定用例和覆盖成绩只适用于旧实验。
当前入口不接受这些 response，也不会用它们充当新 JSON 意图或 RAG 示例。
