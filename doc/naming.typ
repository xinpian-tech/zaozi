#set page(margin: 2cm, numbering: "1")
#set text(lang: "zh")
#show link: underline

== 我的结论

把“名字本身是否贴切”和“是否应该换掉已有品牌”分开看：

- *作为一个全新的独立项目名，我最推荐 `Syntheke`，中文可叫“合契”。*
- *作为实际发布策略，我更推荐保留 `Diplomacy 2.0`，把 `Syntheke` 作为这次重构的架构代号。*
- `Triptych` 很适合命名三阶段架构，但不适合作为仓库名。
- `Diptych` 是最有诗意、也最直接承接 `diplomacy` 词根的备选。

#line(length: 100%)

== `Diplomacy` 的词源其实比“协商”更适合你这次重构

现代英语里的 `diplomacy` 指国家间的交涉与协商，但它最早不是从“谈判”出发的。它经由 `diploma` 而来，而 `diploma` 源自希腊语中“折叠起来的纸张或文书”，词根与“双折、折叠”有关；这种折叠文书后来指官方凭证、护照、授权书，再进一步发展成处理官方文书及国家关系的 `diplomacy`。(#link("https://www.merriam-webster.com/dictionary/diploma?utm_source=chatgpt.com")[Merriam-Webster])

原 Diplomacy 主要使用了这个词的*现代含义*：

#quote(block: true)[节点提出各自的要求，参数双向传播，最终协商出双方都能接受的 edge parameter。]

原仓库也把它直接定义为 Chisel 的 parameter negotiation framework；早期论文描述的核心同样是图上的双向参数传播、约束检查、自由参数协商以及最终 binding。(#link("https://github.com/chipsalliance/diplomacy")[GitHub])

但你这版设计反而同时激活了 `diplomacy` 的两层词义：

=== 1. 协商

```text
Down + Up ──negotiate──▶ Edge
```

Source 和 Sink 各自提出条件，Negotiator 形成协议边。

=== 2. 折叠

```text
UserParam + ProtocolParam ──combine──▶ FullParam
```

用户意图与协议环境被折成一份最终参数。

=== 3. 文书与凭证

`ResolvedDesign` 很像已经签署的条约文本，而 `FullParam` 是唯一必须跨越 Diplomacy ↔ zaozi 边界的可序列化对象。它几乎就像 Generator 接受并验证的一份*外交凭证*：

```text
DesignSpec       = 条款草案
ResolvedDesign   = 已达成的协定
FullParam        = 可执行的正式文书
instantiate(fp)  = 凭文书兑现承诺
```

所以这次重构并没有让 `Diplomacy` 这个名字失效。恰恰相反，它比旧版本更接近这个词的完整词源：*提出条件、达成协议、形成文书、付诸执行。*

#line(length: 100%)

= 第一推荐：`Syntheke`

== 为什么它几乎是为这个架构定制的

希腊语 `syntheke / synthēkē` 表示条约、协议、约定；它还可以表示“条件、情形”。其构词可以理解为“共同放置、把东西放到一起”。(#link("https://referenceworks.brill.com/display/entries/NPOE/e1127530.xml?language=en&utm_source=chatgpt.com")[referenceworks.brill.com])

这几个含义和你的框架惊人地重合：

#table(
  columns: 2,
  table.header([`Syntheke` 的含义], [在框架里的对应]),
  [treaty / agreement], [Down 与 Up 协商出 Edge],
  [conditions], [Protocol parameters、cardinality、拓扑约束],
  [put together], [图、参数、DV 路由、Layer 被组合起来],
  [binding agreement], [`ResolvedDesign` 成为 Phase 3 的执行依据],
)

甚至三阶段都可以自然解释：

```text
Build       — draft the terms
Negotiate   — conclude the agreement
Elaborate   — enact the agreement
```

中文可以不音译，直接使用一个很贴切的名字：

#quote(block: true)[*Syntheke / 合契*]

“合”表示汇合、组合、达成一致；“契”表示契约，也有彼此相合的感觉。

截至我刚才检查时，GitHub 仓库精确搜索 `Syntheke` 没有返回同名仓库，这使它在开源项目中具有很好的辨识度；不过这只是代码仓库重名检查，不等同于商标或域名检索。(#link("https://github.com/search?q=syntheke&type=repositories")[GitHub])

=== 推荐的完整品牌形式

```text
Syntheke
A treaty-driven topology and parameter negotiation framework
for SoC generators.
```

或者更直接：

```text
Syntheke
Declarative topology. Negotiated protocols. Explicit elaboration.
```

最有画面感的一句是：

```text
Draft the graph. Negotiate the terms. Enact the hardware.
```

对应中文：

#quote(block: true)[*起草拓扑，协商条款，兑现硬件。*]

模块名也很自然：

```text
syntheke-core
syntheke-zaozi
syntheke-protocols
```

包路径：


=== 它的缺点

主要问题是陌生。第一次看到的人不知道如何读，也不知道是什么意思，需要 README 第一屏解释。

另外，`Syntheke` 的开头容易让硬件工程师联想到 `synth` 或 synthesis。这有一定好处，因为它听起来像硬件工具；但也可能让人误以为它本身是综合器。副标题必须强调它是 topology and parameter negotiation framework。

#line(length: 100%)

= 第二推荐：保留 `Diplomacy 2.0`

从工程和生态角度，这可能才是最正确的决策。

你的设计虽然删除了 Chisel、CDE、`LazyModuleImp`、`InModuleBody`，但没有删除旧 Diplomacy 的核心身份：

- 仍然是图上的协议参数协商；
- 仍然保留双向参数传播；
- 仍然保留四种 cardinality binding；
- 仍然以协商后的 edge 为连接依据；
- 仍然通过拓扑组合 SoC；
- 只是把“协商”和“硬件构造”之间的边界彻底澄清了。

因此可以这样组织品牌：

```text
Project:      Diplomacy
Release:      Diplomacy 2.0
Codename:     Syntheke
Architecture: The Triptych Pipeline
```

README 开头可以写成：

#quote(block: true)[*Diplomacy 2 is a backend-decoupled topology and parameter negotiation framework for SoC generators. It drafts a structural design, negotiates protocol terms, and enacts the resolved design through zaozi.*]

这种方案的优点是：

+ 保留论文、Rocket Chip 社区和既有用户对 `Diplomacy` 的认知；
+ 明确说明这是同一思想的重构，而不是一个碰巧做类似事情的新库；
+ `Syntheke` 可以承担架构代号，不必承担全部搜索和传播成本；
+ 将来谈迁移时可以自然地说“旧 Diplomacy”与“Diplomacy 2”，而不必不断解释两个项目的关系。

所以我的判断是：

#quote(block: true)[*最佳新名字是 `Syntheke`；最佳发布名字可能仍然是 `Diplomacy 2.0`。*]

#line(length: 100%)

= 第三推荐：`Diptych`

`diptych` 指由两片相连、可以合拢的板或两个相互对应部分组成的作品；它与 `diploma` 一样保留了“折叠、两片”的词源意象。(#link("https://www.merriam-webster.com/dictionary/diptych?utm_source=chatgpt.com")[Merriam-Webster])

它非常适合你设计里反复出现的二元结构：

```text
Down         ↔ Up
UserParam    ↔ ProtocolParam
Source       ↔ Sink
Wrapper      ↔ Generator owner
Design graph ↔ Hardware realization
```

其中最漂亮的是：

```text
Down ─┐
      ├─ fold into Edge
Up   ─┘
```

以及：

```text
UserParam     ─┐
               ├─ fold into FullParam
ProtocolParam ─┘
```

可以配一句：

#quote(block: true)[*Diptych — two-way parameters, one resolved design.*]

它比 `Syntheke` 更直接承接 `diploma / diplomacy` 的词源，也比 `Triptych` 更少绑定具体阶段数。

但它的弱点也明显：

- 第一联想通常是艺术、摄影或双联画，而不是协议协商；
- 它表达了“两部分合拢”，没有表达“双方达成协议”；
- GitHub 精确搜索已有几十个同名仓库，主要集中在图像、艺术和生成式内容领域。(#link("https://github.com/search?q=diptych&type=repositories")[GitHub])

因此我会把它放在：

#quote(block: true)[*最诗意的独立项目名，或者核心参数代数的内部代号。*]

#line(length: 100%)

= `Triptych`：非常适合架构名，不适合项目名

`triptych` 来自“三折、三片”，天然对应：

```text
┌─────────┐   ┌───────────┐   ┌───────────┐
│  Build  │ → │ Negotiate │ → │ Elaborate │
└─────────┘   └───────────┘   └───────────┘
```

而且 Negotiate 正好是位于中间的铰链：

- Build 侧是 JVM-local 的意图和闭包；
- Elaborate 侧是 zaozi、CIRCT 与真实硬件对象；
- Negotiation 把两者隔开并连接起来。

`triptych` 的词源也确实是“具有三个折面”。(#link("https://www.merriam-webster.com/dictionary/triptych?utm_source=chatgpt.com")[Merriam-Webster])

所以我很推荐在设计文档里正式使用：

#quote(block: true)[
*The Triptych Architecture* \
Build → Negotiate → Elaborate
]

或者：

#quote(block: true)[*The Triptych Pipeline*]

但不建议把仓库直接叫 `Triptych`。一方面名字没有协议或 SoC 含义；另一方面它已经是相当拥挤的开源项目名，并且有活跃的 Web/HTML 项目使用。(#link("https://github.com/search?q=triptych&type=repositories")[GitHub])

#line(length: 100%)

= `Symploke`：更像图算法内核

希腊语 `symplokē` 有交织、结合、编织在一起的意思。(#link("https://www.dictionary.com/browse/symploce?utm_source=chatgpt.com")[Dictionary.com])

它很适合这些部分：

- topology graph；
- cardinality 连接分量；
- DV 向上打洞；
- wrapper wire plan；
- Layer prefix tree union；
- 多个模块与协议边交织成 SoC。

因此：

```text
Symploke = topology and wire planning engine
```

很贴切。

但它强调的是*织网*，没有表达“各端提出条件并达成协议”。而 Diplomacy 2.0 最独特的地方仍然不是连线本身，而是连线之前的参数协商。

GitHub 当前只有少量同名精确结果，但已经有一个“连接多个项目”的工具使用这个名称。(#link("https://github.com/search?q=symploke&type=repositories")[GitHub])

我的定位会是：

#quote(block: true)[可以作为 Negotiator 内部图引擎的名字，不作为整个框架的第一选择。]

#line(length: 100%)

== 候选总结

#table(
  columns: 4,
  table.header([名称], [最强隐喻], [最大问题], [推荐用途]),
  [*Syntheke*], [条约、条件、共同组合], [陌生，需要解释], [*全新项目名首选*],
  [*Diplomacy 2.0*], [参数协商与生态延续], [不像彻底换代的新品牌], [*实际发布策略首选*],
  [*Diptych*], [双向参数折叠为一体], [艺术联想强，协商意味弱], [诗意备选],
  [*Triptych*], [三阶段流水线], [同名拥挤，锁定阶段数], [*架构名称首选*],
  [*Symploke*], [图、wire、layer 的交织], [不突出协商], [内部图引擎名称],
  [Accord], [已达成的一致], [太通用], [`ResolvedDesign` 的文档隐喻],
  [Ratify], [协商结果生效], [只覆盖阶段末端], [Phase 2 末端操作名],
  [Enact], [将协定付诸执行], [只覆盖 elaboration], [Phase 3 的文档别名],
  [Pactum], [契约], [已有显著商业项目], [不建议],
  [Foedus], [条约、联盟], [生僻且已有同名软件], [不建议],
  [Ptyx], [折页、叶片], [难读且已有较多项目], [不建议],
)

`Pactum` 已经被一家自动化商业谈判公司使用；`Foedus` 也已有数据库项目；`Ptyx` 虽然词源纯粹，但 GitHub 重名较多，并已有类型系统相关项目。(#link("https://pactum.com/?utm_source=chatgpt.com")[Pactum])

#line(length: 100%)

= 我建议采用的一整套命名

== 方案 A：生态连续性优先

```text
项目：Diplomacy
版本：Diplomacy 2.0
架构代号：Syntheke
流水线：The Triptych Pipeline
```

工程结构维持：

```text
diplomacy-core
diplomacy-zaozi
diplomacy-protocols
```

文档中的两套阶段名称：

```text
代码/API： Build → Negotiate → Elaborate
叙事名称： Draft → Accord → Enact
```

代码里继续使用清晰的工程词，不把诗意命名强塞进 API：


文档则可以说：

```text
The draft is negotiated into an accord,
then enacted through zaozi.
```

这是我最推荐的整体方案。

#line(length: 100%)

== 方案 B：彻底独立品牌

```text
项目：Syntheke
中文：合契
模块：
  syntheke-core
  syntheke-zaozi
  syntheke-protocols
```

README 第一段：

#quote(block: true)[*Syntheke is a treaty-driven topology and parameter negotiation framework for SoC generators. It separates design drafting, protocol negotiation, and hardware elaboration into three explicit phases.*]

与旧项目关系：

#quote(block: true)[*Syntheke is a ground-up Scala 3 successor to CHIPS Alliance Diplomacy.*]

这样既不隐藏血缘，又明确它不是兼容性重写。

#line(length: 100%)

= 还有一个比项目名更应该调整的类型名

按你的不变量，`TerminatorModule` 的本质并不是“位于拓扑末端”：

- 它未必真是 graph terminator；
- 它甚至可以声明 adapter、nexus 等节点；
- 它与 `WrapperModule` 的真正类型差异是：*是否持有一个 zaozi Generator*。

因此这组名字：


没有准确描述 sealed hierarchy 的判别条件。

我会优先考虑：


或者更强调“把协定兑现为硬件”：


其中最不花哨、最不容易误解的是：


`GeneratorModule` 的名字恰好对应你的类型级不变量，读代码的人不需要先理解“terminator”在这里的特殊含义。

#line(length: 100%)

== 我的最终投票

=== 必须换仓库名时

#quote(block: true)[*Syntheke（合契）*]

```text
Syntheke
Draft the graph. Negotiate the terms. Enact the hardware.
```

=== 可以保留原仓库与社区身份时

#quote(block: true)[*Diplomacy 2.0 — Syntheke architecture*]

```text
Diplomacy 2
Declarative topology. Negotiated protocols. Explicit elaboration.
```

同时把三阶段正式称为：

#quote(block: true)[*The Triptych Pipeline*]

这套组合同时保住了历史、词源、架构表达和工程可读性：

```text
Diplomacy 2.0
└── Syntheke architecture
    └── Triptych pipeline
        ├── Build
        ├── Negotiate
        └── Elaborate
```

现在真正需要定的只有一个品牌取向：你更看重旧 `Diplomacy` 的学术与生态连续性，还是希望它被看作一个完全独立的 zaozi 时代新框架？

== References

+ #link("https://www.merriam-webster.com/dictionary/diploma?utm_source=chatgpt.com")
+ #link("https://github.com/chipsalliance/diplomacy")
+ #link("https://referenceworks.brill.com/display/entries/NPOE/e1127530.xml?language=en&utm_source=chatgpt.com")
+ #link("https://github.com/search?q=syntheke&type=repositories")
+ #link("https://www.merriam-webster.com/dictionary/diptych?utm_source=chatgpt.com")
+ #link("https://github.com/search?q=diptych&type=repositories")
+ #link("https://www.merriam-webster.com/dictionary/triptych?utm_source=chatgpt.com")
+ #link("https://github.com/search?q=triptych&type=repositories")
+ #link("https://www.dictionary.com/browse/symploce?utm_source=chatgpt.com")
+ #link("https://github.com/search?q=symploke&type=repositories")
+ #link("https://pactum.com/?utm_source=chatgpt.com")
