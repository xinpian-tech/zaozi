RISC-V 缓存测试指令序列设计指南
====================================

对于缓存实现，指令序列最好不是只验证"能不能读写"，而是要有针对性地覆盖缓存的功能正确性、 边界行为、替换行为、时序相关行为以及一致性/屏障语义。

可以把缓存测试分成下面几类来设计序列。


================================================================================
第一部分：缓存测试序列设计
================================================================================

1. 先明确要测哪些缓存特性
--------------------------------------------------------------------------------

如果是 RISC-V 处理器上的 cache，一般建议覆盖：

 1. 命中/失效（hit/miss）行为
 2. Cache line 填充（line fill）
 3. 替换策略
    - 直接映射
    - 组相联
    - 全相联（若有）
    - LRU / pseudo-LRU / random
 4. 写策略
    - write-through / write-back
    - write-allocate / no-write-allocate
 5. 不同访问粒度
    - byte / halfword / word / doubleword
 6. 跨 cache line 访问
 7. 对齐/非对齐访问
 8. load-store 相关冒险
    - store 后紧跟 load
    - 同地址、不同字节掩码
 9. I-cache / D-cache 交互
    - 自修改代码
    - fence.i
10. 屏障指令语义
    - fence
    - fence.i
11. 异常和边界情况
    - 页边界、权限变化、TLB 配合（若有 MMU）
12. 多核一致性（如果是多核系统）
13. 性能行为
    - 顺序访问
    - 步长访问
    - 冲突访问
    - 工作集大于 cache 容量


2. 最基本：Hit/Miss 序列
--------------------------------------------------------------------------------

这是最先做的。目标是验证：
- 第一次访问某地址是否 miss
- 第二次访问同一地址是否 hit
- cache line 是否正确填充

### 序列 A：同一地址重复读

    la   x1, buf
    lw   x2, 0(x1)      # 预期 miss
    lw   x3, 0(x1)      # 预期 hit
    lw   x4, 0(x1)      # 预期 hit

观察点：
- 第一次访问延迟较长
- 后续访问延迟较短
- 返回数据一致

如果平台有性能计数器，还可以统计：
- load miss 次数
- load hit 次数
- 总周期数


3. Cache line 填充测试
--------------------------------------------------------------------------------

目标是检查 cache line 大小是否按预期工作。

假设 line size 是 64B，那么访问同一 line 内不同字应该只在第一次 miss，后续 hit。

### 序列 B：同一 line 内多点访问

    la   x1, buf
    lw   x2, 0(x1)       # miss
    lw   x3, 4(x1)       # hit（如果同一 line）
    lw   x4, 8(x1)       # hit
    lw   x5, 60(x1)      # hit

然后再跨 line：

    lw   x6, 64(x1)      # 新 line，预期 miss
    lw   x7, 68(x1)      # hit

观察点：
- line 边界前后的行为是否正确
- 一次 fill 是否覆盖整条 line
- 数据是否按正确偏移返回


4. 替换策略测试
--------------------------------------------------------------------------------

这是 cache 测试里非常重要的一类。你要设计多个地址，使它们映射到同一个 set，从而逼出替换。

### 序列 C：直接映射/组相联冲突测试

如果你知道：
- cache size = C
- line size = L
- associativity = A

那么相隔 set_count * line_size 的地址通常会映射到同一个 set。
其中 set_count = C / (L * A)。

例如，构造地址：
- A0
- A1 = A0 + set_count * line_size
- A2 = A1 + set_count * line_size
- ...

这些地址通常同 set 不同 tag。

示例思路：

    la   x1, buf
    li   x10, STRIDE     # STRIDE = set_count * line_size

    add  x2, x1, x0
    add  x3, x1, x10
    add  x4, x3, x10
    add  x5, x4, x10

    lw   x6, 0(x2)       # miss
    lw   x6, 0(x3)       # miss
    lw   x6, 0(x4)       # miss
    lw   x6, 0(x5)       # 若超过组相联度，触发替换

    lw   x7, 0(x2)       # 看是否 hit 或 miss，判断替换策略

用途：
- 验证关联度是否正确
- 验证替换发生时是否替换了预期 line
- 间接推测 LRU / pseudo-LRU / random

### 序列 D：LRU 测试

假设某 set 是 2 路组相联。构造 A、B、C 映射到同一 set：

1. 访问 A，装入
2. 访问 B，装入
3. 再访问 A，使 A 成为最近使用
4. 访问 C，应该淘汰 B（如果是 LRU）
5. 再访问 B，应该 miss
6. 再访问 A，应该 hit

    lw   x10, 0(xA)    # miss, fill A
    lw   x10, 0(xB)    # miss, fill B
    lw   x10, 0(xA)    # hit, A 变 MRU
    lw   x10, 0(xC)    # miss, 应淘汰 B
    lw   x11, 0(xB)    # 预期 miss
    lw   x12, 0(xA)    # 预期 hit

如果结果不同，说明：
- 替换不是 LRU
- 或 set 映射没构造对
- 或有预取器干扰


5. 写策略测试
--------------------------------------------------------------------------------

要明确处理器 D-cache 是：
- write-back 还是 write-through
- write-allocate 还是 no-write-allocate

这决定你应该设计什么序列。

### 序列 E：write hit 测试

    la   x1, buf
    lw   x2, 0(x1)        # 先把 line 拉入 cache
    li   x3, 0x11223344
    sw   x3, 0(x1)        # write hit
    lw   x4, 0(x1)        # 应读回 0x11223344

观察点：
- 写后读是否立即可见
- 是否存在 store buffer 问题
- 字节使能是否正确

### 序列 F：write miss + write allocate / no-write-allocate

    la   x1, buf2
    li   x3, 0x55667788
    sw   x3, 0(x1)        # 该地址先前未访问，write miss
    lw   x4, 0(x1)

预期：
- 如果是 write-allocate：store miss 时会先分配 line，后续 load 应 hit
- 如果是 no-write-allocate：store 可能直接写下层存储，后续 load 不一定 hit

怎么验证：
- 看后续 load 延迟
- 看性能计数器
- 看总线/内存事务（若仿真环境可见）

### 序列 G：write-back 脏块回写测试

核心思想：
1. 将 A 装入 cache 并写脏
2. 用冲突地址把 A 替换出去
3. 检查内存中 A 是否被正确回写

    # 假设 xA, xB, xC 映射到同一 set
    lw   x10, 0(xA)          # fill A
    li   x11, 0xdeadbeef
    sw   x11, 0(xA)          # A 变脏

    lw   x10, 0(xB)          # fill B
    lw   x10, 0(xC)          # 触发替换，A 或 B 被淘汰

    # 再从内存侧/失效后重新读 A，检查是否为 deadbeef
    fence
    lw   x12, 0(xA)

如果在仿真平台上，最好同时观察：
- writeback 事务是否发出
- 回写地址是否正确
- 回写数据是否完整


6. 部分写与字节掩码测试
--------------------------------------------------------------------------------

cache 实现很容易在 byte-enable、merge 逻辑上出错。必须测。

### 序列 H：SB/SH/SW 混合写

    la   x1, buf
    li   x2, 0x11223344
    sw   x2, 0(x1)

    li   x3, 0xAA
    sb   x3, 1(x1)          # 修改第 1 字节

    li   x4, 0xBBBB
    sh   x4, 2(x1)          # 修改高半字

    lw   x5, 0(x1)

检查点：
- 未被覆盖的字节是否保持原值
- little-endian 字节序是否正确
- store merge 是否正确


7. 非对齐和跨 line 访问
--------------------------------------------------------------------------------

如果处理器支持非对齐访存，cache 实现要特别验证跨 line 情况。
如果不支持，也要验证是否正确产生异常。

### 序列 I：跨 line load/store

假设 line size = 64B，选择偏移 62 或 63 附近：

    la   x1, buf
    addi x1, x1, 60
    lw   x2, 0(x1)         # 若跨 line，检查处理是否正确

或者对 RV64：

    addi x1, x1, 60
    ld   x2, 0(x1)         # 很可能跨 64B 边界

关注点：
- 若支持非对齐：返回数据是否正确拼接
- 若不支持非对齐：是否触发异常
- 跨 line 时是否正确发起多次访问


8. 顺序访问与步长访问
--------------------------------------------------------------------------------

这类序列更偏向性能/行为验证，能发现：
- 预取器行为
- 冲突 miss
- 容量 miss
- line 利用率问题

### 序列 J：顺序扫描

    la   x1, array
    li   x2, 1024
loop_seq:
    lw   x3, 0(x1)
    addi x1, x1, 4
    addi x2, x2, -1
    bnez x2, loop_seq

用途：
- 测试空间局部性
- 验证 line fill 后命中率是否提升
- 观察预取器（如果有）

### 序列 K：大步长访问

    la   x1, array
    li   x2, 256
    li   x4, 64            # 或更大步长
loop_stride:
    lw   x3, 0(x1)
    add  x1, x1, x4
    addi x2, x2, -1
    bnez x2, loop_stride

用途：
- 测试不同 stride 下的命中率
- 有些 stride 会导致严重 set 冲突
- 可用于反推出 cache 索引特性


9. 容量 miss 测试
--------------------------------------------------------------------------------

工作集小于 cache 容量时，第二轮访问应大多 hit；
工作集大于 cache 容量时，第二轮访问会出现大量 miss。

### 序列 L：两轮扫描

    # 第一轮：把数据装入 cache
    la   x1, big_array
    li   x2, N
loop1:
    lw   x3, 0(x1)
    addi x1, x1, 4
    addi x2, x2, -1
    bnez x2, loop1

    # 第二轮：重新访问
    la   x1, big_array
    li   x2, N
loop2:
    lw   x3, 0(x1)
    addi x1, x1, 4
    addi x2, x2, -1
    bnez x2, loop2

测试方式：
- 让 N * 4 小于 cache 容量：第二轮应多数 hit
- 让 N * 4 大于 cache 容量：第二轮应出现明显 miss


10. Store-Load 相关序列
--------------------------------------------------------------------------------

这个主要测 D-cache 前端、store buffer、旁路转发。

### 序列 M：store 后立即 load 同地址

    la   x1, buf
    li   x2, 0x12345678
    sw   x2, 0(x1)
    lw   x3, 0(x1)

应检查：
- x3 是否立即等于 0x12345678
- 不应读到旧值
- cache 和 store buffer 的 forwarding 是否正确

### 序列 N：部分写后整字读取

    la   x1, buf
    li   x2, 0
    sw   x2, 0(x1)

    li   x3, 0xAA
    sb   x3, 1(x1)
    lw   x4, 0(x1)

检查：
- 是否正确把部分写合并进 load 结果
- store-to-load forwarding 是否支持 byte mask


11. I-cache 测试
--------------------------------------------------------------------------------

如果你不仅测 D-cache，也测 I-cache，那么最关键的是：
1. 取指 miss/hit
2. 分支跳转到新 line
3. 自修改代码
4. fence.i 语义

### 序列 O：顺序取指跨 line

放一段较长代码，保证跨多个 I-cache line 执行：

loop:
    addi x1, x1, 1
    addi x2, x2, 2
    addi x3, x3, 3
    ...
    bne  x1, x4, loop

用途：
- 检查 I-cache fill
- 检查顺序取指
- 检查跨 line 取指稳定性

### 序列 P：跳转到冷代码区

    jal  x0, target_far

    .space 256             # 确保跨多个 cache line

target_far:
    addi x5, x5, 1

用途：
- 测试非顺序取指
- 分支目标 line 装填
- BTB/前端和 I-cache 配合

### 序列 Q：自修改代码 + fence.i

这是 I-cache 测试的重点。

流程：
1. 先执行一段代码
2. 用 store 修改这段代码对应的内存
3. 执行 fence.i
4. 再跳回去执行，检查是否执行的是新指令

伪代码思路：

    # code_area 初始是一条 addi x10, x0, 1
    jal  ra, code_area
    # 返回后 x10 = 1

    # 修改 code_area 的机器码，变成 addi x10, x0, 2
    la   x1, code_area
    li   x2, NEW_INSN
    sw   x2, 0(x1)

    fence.i
    jal  ra, code_area
    # 返回后 x10 应 = 2

这是必须测的点——因为 D-cache 写入的代码如果 I-cache 不失效，可能仍执行旧代码。


12. fence / fence.i 语义测试
--------------------------------------------------------------------------------

RISC-V 对内存顺序和指令可见性要求需要 cache 配合实现。

### 序列 R：fence 测试

在单核上更多是检查：
- store 是否在 fence 后对后续 load/外设访问可见

    sw    x2, 0(x1)
    fence rw, rw
    lw    x3, 0(x1)

单核里不一定容易观察，但在带 DMA/外设/多核时很重要。

### 序列 S：fence.i 测试

就是前面的自修改代码测试（序列 Q）。这是 I-cache 正确性核心用例。


13. 多核一致性测试（如果处理器是多核）
--------------------------------------------------------------------------------

如果有多核，共享内存 + 私有 cache 时，必须测：
1. 一个核写，另一个核读是否可见
2. 失效/更新机制是否正确
3. 原子指令与 cache 的一致性

### 序列 T：核间可见性

核 0：

    li   x2, 1
    sw   x2, 0(flag_addr)
    fence rw, rw

核 1：

wait:
    lw   x3, 0(flag_addr)
    beqz x3, wait

如果有 cache 一致性协议，还要测数据区：
- 核 0 写 data 再写 flag
- 核 1 先等 flag，再读 data
- 检查是否读到新 data

### 序列 U：原子指令和 cache

例如 lr/sc：

    la   x1, lock
retry:
    lr.w x2, (x1)
    bnez x2, retry
    li   x3, 1
    sc.w x4, x3, (x1)
    bnez x4, retry

检查点：
- reservation 是否被其他核写正确打断
- cache coherence 是否与 LR/SC 正确协作


14. 异常边界测试
--------------------------------------------------------------------------------

cache 实现和 MMU/TLB 交互也容易出错。

建议测：
1. 页边界跨越访问
2. cacheable / uncacheable 区域切换
3. 权限异常
4. 访存异常时是否错误污染 cache

### 序列 V：页边界附近访问

如果有虚拟内存：
- 在页尾做 load/store
- 跨页访问
- 一页可读、一页不可读

检查：
- 是否正确触发 page fault
- 是否不会把错误数据填入 cache


15. 随机压力序列
--------------------------------------------------------------------------------

除了定向用例，还建议做随机测试。

但随机不是纯乱发，要"受控随机"：
- 地址范围可控
- 访问类型随机（lb/lh/lw/ld/sb/sh/sw/sd）
- 步长随机
- 混合读写比例
- 周期性插入 fence
- 定期校验内存镜像

思路：
1. 软件维护一个 reference memory model
2. 每条随机 store 同步更新 reference
3. 每次 load 对比 reference 结果
4. 周期性做 cache thrash 和替换

这样容易抓出：
- 脏数据丢失
- 字节掩码错误
- 替换时 tag/data 错配
- 罕见时序 bug


16. RTL 验证常见 bug
--------------------------------------------------------------------------------

实际 cache RTL 里常见问题包括：

 1. tag 命中但 data lane 错
 2. 字节掩码合并错误
 3. dirty 位更新错误
 4. 替换路选择错误
 5. 回写地址 tag 拼接错误
 6. miss 期间又来新请求，状态机处理错
 7. flush/invalidate 中丢请求
 8. I/D cache 一致性问题
 9. uncacheable 访问被错误 cache
10. 异常返回数据污染 cache
11. 跨 line 访问拼接错误
12. store-load forwarding 掩码错误

所以测试序列一定要覆盖这些触发条件。


17. 推荐的最小测试集
--------------------------------------------------------------------------------

如果你想先做一套"够用"的 cache 测试序列，至少包含下面 10 类：

 1. 同地址重复读：测 hit/miss
 2. 同 line 多地址读：测 line fill
 3. 跨 line 读：测 line 边界
 4. 冲突地址访问：测替换
 5. 脏块替换：测 write-back
 6. write miss：测 write-allocate / no-write-allocate
 7. SB/SH/SW 混合写：测字节掩码
 8. store 后立即 load：测 forwarding
 9. 自修改代码 + fence.i：测 I-cache
10. 大工作集双轮扫描：测容量 miss/性能


18. 测试组织方法
--------------------------------------------------------------------------------

建议按下面方式组织你的指令序列：

第一层：功能正确性
  - 数据读写正确
  - 替换正确
  - 脏块回写正确

第二层：边界条件
  - line 边界
  - 页边界
  - 非对齐
  - partial store

第三层：微架构交互
  - store buffer
  - miss under miss
  - fill 过程中命中
  - flush/fence

第四层：系统语义
  - I/D 一致性
  - 多核一致性
  - 原子操作
  - MMU 属性

第五层：性能/压力
  - 顺序流
  - stride
  - 随机
  - 冲突工作负载


19. 黑盒测试：不知道 cache 参数时的设计方法
--------------------------------------------------------------------------------

如果 cache 大小、line 大小、组相联度未知，也可以这样测：

1. 先通过步长扫描推测 line size
   - 访问间隔 4/8/16/32/64/128B，观察 miss 变化

2. 通过冲突地址数推测相联度
   - 固定疑似同 set 地址，逐渐增加地址个数，看何时开始替换

3. 通过工作集大小推测容量
   - 双轮扫描，不断扩大数组

这类序列常用于黑盒测试。


20. 结论
--------------------------------------------------------------------------------

对于缓存实现，测试序列应重点覆盖：
- 命中/失效
- line 填充
- 替换策略
- 写策略（write-back / write-through, allocate / no-allocate）
- 部分写与字节掩码
- 跨 line / 非对齐访问
- store-load 转发
- I-cache 与 fence.i
- 容量/冲突/顺序访问行为
- 多核一致性和原子操作（若支持）


================================================================================
第二部分：如何观察缓存命中/失效
================================================================================

一、性能计数器法（最直接）
--------------------------------------------------------------------------------

RISC-V 有 mcycle 和 minstret 计数器，有些平台还有专用 cache 事件计数器
（如 L1D_ACCESS、L1D_MISS）。

如果处理器实现了相关性能计数器，可以直接读取。

示例代码：

    # 启用计数器
    csrw mcounteren, 0x7       # 允许用户态读 cycle/instret

    # 读 cycle 计数
    rdcycle x10
    # 执行测试指令序列
    rdcycle x11
    sub x12, x11, x10          # 得到 cycle 差


二、时序分析法（无专用计数器时）
--------------------------------------------------------------------------------

通过测量指令执行时间差来推断命中/失效。

核心思想：第一次访问（预期失效）时间较长，第二次访问（预期命中）时间较短。

基本时序测试模板：

    # 对齐到 cache line 边界（假设 line size = 64B）
    .align 6
test_data:
    .word 0x12345678

    # 第一次访问（预期失效）
    rdcycle x10
    la  x1, test_data
    lw  x2, 0(x1)
    rdcycle x11
    sub x12, x11, x10         # 时间 T1

    # 第二次访问（预期命中）
    rdcycle x13
    lw  x3, 0(x1)
    rdcycle x14
    sub x15, x14, x13         # 时间 T2

    # 比较 T1 和 T2
    # 通常 T1 > T2 表示第一次是失效，第二次是命中

注意：现代处理器可能有预取、乱序执行，简单的 rdcycle 可能不够精确。

可以：
1. 多次重复取平均
2. 插入 fence 指令确保顺序
3. 使用 rdtime（如果有）获得更高精度时钟


三、地址冲突法（间接推断）
--------------------------------------------------------------------------------

通过控制地址映射到同一个 cache set，观察访问时间变化。

示例：探测相联度

    # 假设 cache line size = 64B，有 8 路
    # 构造映射到同一个 set 的 8 个地址
    .set LINE_SIZE, 64
    .set NUM_WAYS, 8

    la x1, base_addr
    li x2, LINE_SIZE * NUM_WAYS    # 步长

    # 顺序访问这 8 个地址（全部失效）
    rdcycle x10
    lw x3, 0(x1)
    add x1, x1, x2
    lw x4, 0(x1)
    add x1, x1, x2
    # ... 重复 8 次
    rdcycle x11

    # 再次访问第一个地址
    rdcycle x12
    la x1, base_addr
    lw x5, 0(x1)              # 如果 8 路都被占用，且 LRU 替换，这里应该命中
    rdcycle x13

如果第二次访问时间短 → 命中
如果第二次访问时间长 → 可能被替换出去了（失效）


四、工作集容量法
--------------------------------------------------------------------------------

容量失效测试：

    # 创建大于 cache 容量的数组
    .set ELEM_SIZE, 4
    .set CACHE_SIZE, 16384         # 假设 16KB cache
    .set ARRAY_SIZE, CACHE_SIZE * 4 / ELEM_SIZE   # 4 倍 cache 容量

    la x1, big_array
    li x2, ARRAY_SIZE

    # 第一轮：全部装入 cache（容量失效）
    rdcycle x10
loop1:
    lw   x3, 0(x1)
    addi x1, x1, ELEM_SIZE
    addi x2, x2, -1
    bnez x2, loop1
    rdcycle x11

    # 第二轮：重新访问
    la x1, big_array
    li x2, ARRAY_SIZE
    rdcycle x12
loop2:
    lw   x3, 0(x1)
    addi x1, x1, ELEM_SIZE
    addi x2, x2, -1
    bnez x2, loop2
    rdcycle x13

    # 计算两轮时间差
    sub x14, x11, x10             # T1
    sub x15, x13, x12             # T2
    # 如果 T2 显著大于 T1，说明容量失效频繁


五、软件模拟法（最精确但复杂）
--------------------------------------------------------------------------------

在仿真环境中，通过脚本或硬件接口直接读取 cache 状态。

在 QEMU/Spike 等仿真器中：

    # 使用 trace 功能
    spike --log-cache-miss my_program
    # 或
    qemu-riscv64 -d cache my_program

通过自定义 CSR（如果实现）：

    # 假设实现了 cache 统计 CSR
    csrr x10, mhpmcounter3         # L1D miss 计数
    # 执行测试
    csrr x11, mhpmcounter3
    sub  x12, x11, x10             # miss 次数


六、实际测试技巧
--------------------------------------------------------------------------------

1. 消除干扰因素：

    # 测试前清空 cache（如果支持）
    fence.i                        # 清空 I-cache
    # 对于 D-cache，通常需要访问大于 cache 容量的数据来"冲刷"

    # 确保测试地址是 cacheable 的
    # 使用对齐地址避免非对齐访问的开销

2. 提高测量精度：

    # 多次循环取平均
    li x5, 1000                    # 循环次数
    rdcycle x10
test_loop:
    lw   x3, 0(x1)
    addi x5, x5, -1
    bnez x5, test_loop
    rdcycle x11

3. 避免预取影响：

    # 使用随机或大跨步访问模式
    # 或者插入屏障指令
    lw    x3, 0(x1)
    fence
    lw    x4, 0(x1)


七、命中/失效判断经验值
--------------------------------------------------------------------------------

  场景              | 预期结果 | 判断方法
  ------------------|----------|--------------------------------------------
  冷启动访问        | 失效     | 时间较长（通常是命中的 2-10 倍）
  立即重复访问      | 命中     | 时间较短
  同一 cache line 内| 命中     | 与第一次访问同一 line 的偏移地址，时间接近命中时间
  冲突地址替换后    | 失效     | 访问时间回到冷启动水平
  容量失效          | 失效     | 工作集超过 cache 容量时，时间显著增加


八、实用测试序列示例
--------------------------------------------------------------------------------

综合命中/失效测试：

    # 测试 1：基本命中/失效
    .macro HIT_MISS_TEST addr, expected_hit
        rdcycle x10
        lw x11, \addr
        rdcycle x12
        # 如果 (x12 - x10) < HIT_THRESHOLD，则可能是命中
    .endm

    # 测试 2：line 填充验证
    la x1, base
    lw x2, 0(x1)              # 失效
    lw x3, 4(x1)              # 应命中（同一 line）
    lw x4, 8(x1)              # 应命中
    lw x5, 64(x1)             # 失效（不同 line）

    # 测试 3：替换策略验证
    # 构造映射到同一 set 的多个地址
    la x1, A
    la x2, B
    la x3, C
    lw x4, 0(x1)              # 失效
    lw x5, 0(x2)              # 失效
    lw x6, 0(x3)              # 失效
    lw x7, 0(x1)              # 根据替换策略，可能命中或失效


九、自动化判断建议
--------------------------------------------------------------------------------

1. 建立基线：

    # 测量纯命中时间（通过密集访问同一地址）
    li x20, 1000
    rdcycle x10
hit_loop:
    lw   x11, 0(x1)
    addi x20, x20, -1
    bnez x20, hit_loop
    rdcycle x11
    # 计算平均命中时间

2. 设定阈值：
   - 命中时间阈值 = 平均命中时间 x 1.5
   - 超过阈值判断为失效

3. 统计分析：
   - 记录每次访问时间
   - 计算命中率 = (总访问次数 - 失效次数) / 总访问次数
