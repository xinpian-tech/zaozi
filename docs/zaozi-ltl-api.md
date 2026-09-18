# zaozi LTL API 参考

按 2026-09-14 当前工作树的实际声明、实现和测试整理。这里的 LTL API 包含 SVA 的时序序列与属性，不只是布尔公式。本文记录已有实现，也不包含任何实验设计的历史答案。

源码入口：[API 声明](../zaozi/src/Api.scala)、[默认实现](../zaozi/src/default/SVAApi.scala)、[LTL 类型](../zaozi/src/ltltpe/)、[SVASpec](../zaozi/tests/src/SVASpec.scala)、[PastSpec](../zaozi/tests/src/PastSpec.scala)。

## 1. 上下文与类型

底层 API 在已有 generator 的 `architecture` 中使用。RVProbe 的 `runtime-ltl-v4` 接口由框架预先提供以下上下文及端口局部别名，模型不要输出这些声明，只返回 LTL 片段：

```scala
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// io 由当前模块的 Interface 提供。
given ClockEvent = posedge(io.clock)
```

下文示例都是该上下文中的表达式片段，不是独立 UT。`p`、`q`、`r` 是 `Referable[Bool]`，`bits` 是 `Referable[Bits]`；拍数是 Scala `Int`，不是硬件信号。所有名字均为符号占位，不是 DUT 场景。

RVProbe 额外预导入框架的 [Ltl helper](../utlib/src/Ltl.scala)：`Ltl.is(signal, value: BigInt)` 按信号类型与位宽构造常量比较；`Ltl.isZero(signal)` / `Ltl.isOnes(signal)` 判断全零/全一，返回硬件 Bool。支持 Bits/UInt/SInt，越界常量报错而不截断；SInt 全一是 -1。它们不引入时序或约束。模型只调用这些 API，不输出 helper 定义；[skill](../rvprobe-skill.md) 提供调用示例。

大常量使用 `BigInt("89abcdef", 16)`，不要用 `BigInt(0x89abcdef)`：后者在 Scala 中先成为负数 Int，BigInt 无法恢复溢出前的值。全零/全一直接用现有 helper。越界会抛出带源码位置及错误码的 `LtlArgumentException`；RVProbe 仅将可映射到模型源码的有符号/无符号范围错误作为可修正的 `elaboration-check`，其他 lower 错误不自动交给模型。

省略签名中重复的 elaboration 上下文：`Arena`、MLIR `Context/Block`、`sourcecode` 信息和 `InstanceContext`。标注“需要时钟”特指额外的 `using ClockEvent`，不是要求调用者手工创建这些编译上下文。

| 类型 | 含义 | 构造例子 |
| --- | --- | --- |
| `Referable[Bool]` | 单个硬件布尔表达式 | `p & q`、`!p` |
| `Immediate`（下文 I） | 不附带采样时钟的布尔原子/组合 | `p.I` |
| `Sequence`（下文 S） | 有起点、终点的时序匹配 | `p.S`、`p.S ### q.S` |
| `Property`（下文 P） | 属性，可包含否定、蕴含、直到等 | `p.S \|-> q.S` |

`I`、`S`、`P` 不是任意互换的包装。尤其 `!p.S` 返回 `Property`，而 `(!p).S` 返回 `Sequence`。

`Immediate` 不等于 SystemVerilog 的 procedural immediate assertion：当前 `Assert(p.I)` 输出的是 `assert property (p)`，并非 `assert (p)`。

### 时钟与原子

| API | 返回类型 | 说明 |
| --- | --- | --- |
| `posedge(clock: Referable[Clock])` | `ClockEvent` | 上升沿采样事件 |
| `negedge(clock: Referable[Clock])` | `ClockEvent` | 下降沿采样事件 |
| `event(body)` | `body` 的结果类型 | 在 `body` 内提供该 `ClockEvent` |
| `p.S` | S | 需要时钟；创建 clocked atom |
| `p.I` | I | 不需要时钟；不自动附加采样事件 |

例如 `posedge(io.clock)(p.S)` 可以局部绑定时钟。延迟、重复等操作也使用调用位置的 `ClockEvent`，不会只从操作数推断时钟；已有原子的时钟不会因此被重写。多时钟嵌套存在生成测试，但不意味着任意组合已经通过 RVProbe 的求解与回放验证。

`ClockScope`/`ResetScope` 不能代替 LTL 的 `ClockEvent`，也不会自动为这些 LTL 表达式添加复位禁用或历史有效性保护。

## 2. 序列：连接、延迟与重复

`###` 和 `##` 的连接重载也接受硬件 Bool，不必显式写 `.S`：

```scala
p ### q                        // p.S ### q.S
p.##(q)                        // p.S.##(q.S)，零拍间隔
p.##(gap)(q)                   // p.S.##(gap)(q.S)
p.##(lo, Some(hi))(q)          // p.S.##(lo, Some(hi))(q.S)
p ### q ### !p                // 支持返回的 Sequence 继续与 Bool 连接
```

左右两侧可混用 Bool 和 Sequence；只在连接操作处按当前 ClockEvent 提升 Bool，
保留已有 Sequence 的时钟。Bool 参与的重载都需要 ClockEvent，包括零延迟连接。
这不是全局隐式转换，不改变硬件 `& / | / !`、数值比较或 past 的类型；
Bits、UInt、SInt 和 Property 不能直接作为连接操作数。
重复和其他 sequence 专用操作仍可显式使用 `.S`。

以下 `s`、`a`、`b` 均为 `Sequence`，返回类型也均为 `Sequence`。除了 `a.##(b)`，本节表内操作都需要时钟。

| zaozi 调用 | SVA 对应写法/含义 |
| --- | --- |
| `a.##(b)` | `a ##0 b`：a 的终点与 b 的起点重合 |
| `a.###(b)` 或 `a ### b` | `a ##1 b`：b 从 a 结束后的下一拍开始 |
| `a.##(n)(b)` | `a ##n b` |
| `a.##(lo, Some(hi))(b)` | `a ##[lo:hi] b`，上下界均包含 |
| `a.##(lo, None)(b)` | `a ##[lo:$] b`，没有有限上界 |
| `a.##+(b)` | `a.##(1, None)(b)` |
| `a.##*(b)` | `a.##(0, None)(b)` |
| `s.*(n)` 或 `s * n` | `s[*n]`：连续重复 n 次 |
| `s.*(lo, Some(hi))` | `s[*lo:hi]`：连续重复次数范围 |
| `s.*(lo, None)` | `s[*lo:$]`；`lo=0/1` 分别可输出 `[*]` / `[+]` |
| `s.*->(lo, hi)` | `s[->lo:hi]`：goto repetition，匹配结束在最后一次命中 |
| `s.*=(lo, hi)` | `s[=lo:hi]`：nonconsecutive repetition，允许最后一次命中之后的未命中尾段 |
| `a.within(b)` | 将 a 放在 b 的匹配区间内；当前展开式见下文 |

参数检查：固定延迟/重复要求 `n >= 0`；范围要求 `lo >= 0` 且 `hi >= lo`。这些是 elaboration 时检查的常数。

`*->` 和 `*=` 只有两个 `Int` 参数的重载：固定次数写成 `p.S.*->(n, n)` / `p.S.*=(n, n)`，没有单参数或 `None` 上界版本。虽然 Scala 接收 `Sequence`，对应 SVA 重复形式有操作数限制；这里应使用 `p.S` 这样的单拍布尔原子，不把类型签名视为任意复合序列都能在后端使用的保证。

注意：

- `p.S.##(n)(q.S)` 只指定两个端点，中间拍不隐含任何输入条件。
- 重复的是整个 `s`，不是只保持最后一拍。`p.S.*(n)` 才表示 p 连续 n 个采样点为真。
- `.*(0)` 涉及空序列；它不等于 `##0` 的同拍连接。
- 明确写 `.##(n)(...)`，不要使用易产生 Scala 解析歧义的 `a ##(n)(b)`。
- `None` 表示语义上的无界，不是 RVProbe 自动设置了某个求解深度。

### throughout 与 within

```scala
p.throughout(s)   // p 是 Bool，s 是 Sequence；返回 Sequence，需要时钟
a.within(b)      // a、b 都是 Sequence；返回 Sequence，需要时钟
```

`throughout` 要求 p 在 s 的整个匹配区间成立，默认实现为布尔原子的零到无穷次重复与 s 做 `intersect`。

`within` 当前实现的准确展开为：

```scala
true.B.S.##*(a).##*(true.B.S).intersect(b)
```

两者已有 Verilog 生成测试；空序列、无界和多时钟的组合边界不能仅凭这些测试视为完成端到端验证。

## 3. 逻辑组合与返回类型

`&`、`|`、`intersect` 三个操作共享以下重载返回类型；它们自身不额外要求时钟。

| 左操作数 ＼ 右操作数 | I | S | P |
| --- | --- | --- | --- |
| I | I | S | P |
| S | S | S | P |
| P | P | P | P |

例如 `p.I & q.S` 返回 S，`p.S & !q.S` 返回 P。

- `&` 输出 LTL/SVA 的 `and`；序列组合要求共同起点，但两侧可以在不同拍结束。
- `intersect` 对序列还要求终点一致；不要把它与 `&` 视作时序上的同义词。
- `|` 输出 `or`。
- `!x`（方法名 `unary_!`）对 I、S、P 都返回 P（property negation）。

硬件 Bool 层面的 `p & q` / `p | q` / `!p` 则仍是硬件布尔运算。不要用 Scala `&&` / `||`，比较式应先加括号，例如 `(bits === value.B(width)) & p`。

## 4. 蕴含、followed-by 与时序属性

下表中 `x`、`y` 表示 I/S/P；所有结果均为 P。

| 调用 | 左操作数限制 | 需要时钟 | 当前语义/展开 |
| --- | --- | --- | --- |
| `a.\|->(y)` | I 或 S | 否 | 重叠蕴含：后件从前件匹配结束处开始 |
| `a.\|=>(y)` | 仅 S | 是 | 非重叠蕴含：`a.##(1)(true.B.S).\|->(y)` |
| `a.#-#(y)` | I 或 S | 否 | 重叠 followed-by：`!(a.\|->(!y))` |
| `a.#=#(y)` | 仅 S | 是 | 非重叠 followed-by：`!(a.\|=>(!y))` |
| `x.implies(y)` | I/S/P | 否 | 逻辑蕴含，展开为 `(!x) \| y` |
| `x.iff(y)` | I/S/P | 否 | 展开为 `(!(x \| y)) \| (x & y)` |
| `x.until(y)` | I/S/P | 是 | 输出 `x until y` |
| `x.untilWith(y)` | I/S/P | 是 | 输出 `x until (x and y)`，结束拍也要求 x |
| `always(x)` | 参数 I/S/P | 是 | 输出 `x until false` |
| `eventually(x)` | 参数 I/S/P | 是 | 输出 `s_eventually x` |

表中的 `\|` 只是 Markdown 表格转义，实际 Scala 操作符是 `|`。可直接使用中缀形式，例如：

```scala
val overlapped = p.S |-> q.S
val nextCycle = p.S |=> q.S
val logical = p.I implies q.I
```

不要混淆 `implies` 与 `|->`：后者使用序列匹配终点；前者是逻辑组合展开。`Property` 没有 `|->`、`|=>`、`#-#`、`#=#` 扩展；`Immediate` 没有 `|=>`、`#=#`。

`until` 使用弱直到，不要求右侧最终发生；`untilWith` 额外要求终止拍的左侧条件。`eventually` 则输出强最终发生 `s_eventually`。`always` 和无界 eventually 不等于一个默认有限长度的重复序列。

蕴含有空真问题：前件不发生也可能满足属性。若验证意图是“实际产生 p，再产生 q”，应直接描述 `p.S.##(n)(q.S)`，而不是靠 `p.S |=> q.S` 强制 p 发生。`#-#` / `#=#` 是上述否定蕴含展开，不是蕴含的别名。

## 5. past：历史值，不是新的 Gen 分类

```scala
def past[D <: Bool | UInt | SInt | Bits](
  value: Referable[D],
  delay: Int = 1
)(using ClockEvent)(using
  java.lang.foreign.Arena,
  org.llvm.mlir.scalalib.capi.ir.Context,
  org.llvm.mlir.scalalib.capi.ir.Block,
  sourcecode.File,
  sourcecode.Line,
  sourcecode.Name.Machine,
  InstanceContext
): Node[D]
```

`past(value, n)` 返回当前采样事件之前 n 次采样的值，保留 Bool/UInt/SInt/Bits 类型及位宽。`n` 必须大于 0；不接受 Clock、Bundle、Sequence 或 Property。

默认实现明确输出采样时钟，例如：

```systemverilog
$past(bits, 2, , @(posedge clock))
```

`past` 是采样值表达式，不是自动插入 n 级可复位寄存器。它没有 reset 参数、初值参数、采样 gate 参数，也没有自动历史有效性保护。

```scala
val changed = !(past(bits, gap) === bits) // gap > 0，结果是硬件 Bool
val observedChange = p.S.##(gap)(changed.S)
```

第二式先匹配 p，再等待 gap 拍比较当前值与 p 所在拍的值，使该比较拥有所需的采样历史。它不要求中间值稳定，也不保证历史区间没有复位。如果意图有这些要求，应在表达式中明确写出；不能偷偷添加全局 Assume。

单独写 `changed` 则没有建立有效历史起点。刚开始采样、复位前后以及嵌套 past 的有效历史长度必须另行分析，不能假定未知历史就是零。

当前 SVAApi 没有独立的 `rose`、`fell`、`stable`、`changed` 方法。对于 Bool，在历史有效的前提下可写 `p & !past(p)` / `!p & past(p)` 表达相邻采样的上升/下降关系；这不是对所有四态 SVA 系统函数语义的完整替代声明。

## 6. Assert / Assume / Cover

三者有相同的四组调用形式，其中 `expr` 必须是 I/S/P，`enable` 是 `Referable[Bool]`：

```scala
Assert(expr)
Assert(expr, enable)
Assert(expr, "label")
Assert(expr, enable, "label")
// 将 Assert 换成 Assume 或 Cover，重载完全相同。
```

均返回 `Unit`。调用本身不额外要求时钟；时钟由表达式构造提供。不支持直接传硬件 Bool，应写 `p.I` 或 `p.S`。省略 label 时，实现使用调用位置的 `sourcecode.Name.Machine` 名称信息。

| 入口 | 作用 |
| --- | --- |
| `Assert` | 发出待检查的属性 |
| `Assume` | 发出限制环境可接受行为的属性 |
| `Cover` | 发出寻找满足该属性的执行/匹配的目标 |

`enable` 在当前生成测试中降低为 `disable iff (!enable)`，不是简单的 `enable & expr`，也不是 `$past` 的采样 gate。例如 `Assert(expr, !resetBool, "check")` 输出 `disable iff (resetBool)`。

相关但独立的 [ContractApi](../zaozi/src/default/ContractApi.scala) 也接收 LTL 属性：`Require(expr)` / `Require(expr, label)` 和 `Ensure(expr)` / `Ensure(expr, label)` 均接收 I/S/P、返回 Unit。在 Contract 内它们分别构造前置/后置条件；在 Contract 外当前实现分别发出 Assume/Assert。因此它们不是绕过 RVProbe 实验约束的另一种写法。Contract 的数据绑定接口不属于本文的 LTL 表达式 API 范围。

## 7. RVProbe 的单一入口 Gen

这部分来自 [utlib/Gen.scala](../utlib/src/Gen.scala)，不是 zaozi 原生 SVAApi 的另一组操作符。

```scala
import me.jiuyang.utlib.*

Gen(p & q, "both")
Gen(p.S.##(gap)(q.S), "ordered")
```

准确接口为 `Gen.apply(goal: Gen.Expr, label: String): Unit`，需要 `ClockEvent` 和编译上下文。

```scala
type Expr = Referable[Bool] | Sequence | Property
```

`Gen.Expr` 只是类型别名，不需要写成工厂调用；直接 `Gen(expression, label)`。目前不接受 `Immediate`，也没有 `Gen.past`。

实现将 Bool 提升成 `.S` 后调用 `Cover`，S/P 则直接交给 `Cover`。求 witness，不做全称证明；“Scala 接受 Property”不代表任意无限时域属性都能求解并回放。

RVProbe 当前实验作者契约只允许用 Gen 表达目标，不允许模型另加 `Assume`、`restrict`、额外 `Assert/Cover`，或替换 DUT。zaozi 支持这些验证语句，不等于实验允许模型使用它们。可用 DUT 输出作为目标条件，但生成的激励只能驱动输入，输出必须由 DUT 自身产生。

### 可以简化表达式的通用例子

```scala
// 连续 n 拍 p 为真，然后下一拍 q 为真；n >= 1。
val held = p.S.*(n)
val followed = held ### q.S
Gen(followed, "hold_then_event")

// p 到 q 的延迟可在范围内变化，且 r 在整个匹配区间成立。
val window = p.S.##(lo, Some(hi))(q.S)
Gen(r.throughout(window), "condition_over_window")
```

`p.S.*(n)` 可以替代对相同单拍谓词的 n 次 `###` 手工展开。`throughout` 可以避免逐拍重复同一条件；它不能替代“每一拍都有不同条件”的序列。选用这些形式不应改变原始意图，也不应额外缩小输入空间。

## 8. 验证范围与维护

必须区分三个层次：

1. API 声明允许某种类型和调用。
2. 默认实现可以将某些用例生成为预期的 MLIR/SystemVerilog。
3. 具体 RVProbe 后端能对目标求解，并在实际 DUT 回放中满足原始表达式。

本文完整列出了当前 `SVAApi` 的公开调用族与重载组合，但没有把第二层的测试结果包装成第三层的能力保证。尤其无界属性、空序列、复杂非连续重复和跨时钟组合，需要额外的端到端测试。

2026-09-13 在当前工作树执行以下命令，51 个测试通过（SVASpec 49 个、PastSpec 2 个），无失败。测试覆盖主要操作符的生成、时钟选择、非法延迟，以及 past 的类型/位宽和上升/下降沿输出：

```bash
nix develop -c mill zaozi.tests.testOnly \
  me.jiuyang.zaozitest.SVASpec me.jiuyang.zaozitest.PastSpec
```

这些是本地编译/生成测试，不调用 DeepSeek，也不是一次新的 JG 实验。`FrameworkLtlTest` 另有 Gen 的基本表达式生成测试，但本次命令没有执行它。

三个 LTL 类型还提供低层桥接方法 `refer(using Arena, TypeImpl): Value` 和 `toMlirType(using Arena, Context, TypeImpl): Type`；这些用于编译器集成，不是验证意图的书写 API。

RVProbe 的短 skill 提供核心表达式用法，RAG 只按需开放第 2–5 节的原文摘录及 Gen 接受类型。不要把整篇参考文档无条件塞进每轮 prompt；示例只包含通用语法，不补入任何 benchmark 的历史激励、固定操作数或答案。
