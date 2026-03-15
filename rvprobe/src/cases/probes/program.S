    .section .text
    .globl  _start

_start:
    la      t0, trap_handler
    csrw    mtvec, t0

    csrr    t0, mstatus
    li      t1, 0x00003000       # set FS field to Dirty (0b11) so FP regs usable
    or      t0, t0, t1
    csrw    mstatus, t0
    csrw    fcsr, x0             # clear floating-point status
    csrw    mscratch, x0         # clear mscratch

    # 放开 PMP：一条 NAPOT 覆盖全地址空间，允许 S 模式取指/访存
    li      t0, -1
    csrw    pmpaddr0, t0
    li      t0, 0x1f             # R|W|X | A=NAPOT
    csrw    pmpcfg0, t0
    
    j       user_code

user_code:
    li x8, 0x774492720dbedb91
    fmv.d.x f16, x8
    li x8, 0x271141afdb5a2f58
    fmv.d.x f17, x8

    fround.s f17, f16, dyn
    jal ra, switch_to_s_mode

    li x11, 0x40000000
    ld x12, 0(x11)
    fsub.s f16, f16, f17, rtz
    fround.s f17, f16, dyn

exit:
    li      t0, 1                # report success
    la      t1, tohost
    sd      t0, 0(t1)
1:
    j       1b

.align 2
switch_to_s_mode:
    # 配置页表：恒等映射 0x80000000 所在的 1GB 区域
    la      t0, pgtbl
    li      t1, (0x80000 << 10) | 0xCF # PPN=0x80000, 权限全开
    sd      t1, 16(t0)

    # 写入 satp 开启分页 (Sv39)
    li      t2, 8
    slli    t2, t2, 60
    srli    t1, t0, 12
    or      t2, t2, t1
    csrw    satp, t2
    sfence.vma

    # 修改 MPP 为 S-mode (1)，准备 mret
    li      t0, ~(3 << 11)
    csrr    t1, mstatus
    and     t1, t1, t0
    li      t2, (1 << 11)
    or      t1, t1, t2
    csrw    mstatus, t1

    csrw    mepc, ra
    mret

.align 2
exit_s_mode:
    ecall                        # 触发 Environment Call (Cause 9)
    ret                          # 实际上由 handler 修改 mepc 跳过此处

    .align  2
trap_handler:
    csrr    t0, mepc             # faulting PC
    csrr    t1, mcause           # trap cause
    csrr    t4, mtval

    # 提取异常代码
    slli    t5, t1, 1
    srli    t1, t5, 1

    # --- 新增：处理回到 M 模式的请求 ---
    li      t3, 9                # Ecall from S-mode
    bne     t1, t3, check_page_fault
    # 如果是 ecall，修改 MPP 并在 mret 后回到 M 模式
    li      t2, 3 << 11
    csrs    mstatus, t2          # 将 MPP 设为 3 (Machine)
    li      t2, 4                # 跳过 ecall 指令
    j       update_mepc

check_page_fault:
    # --- 新增：识别页错误 (13 或 15) ---
    li      t3, 13
    beq     t1, t3, handle_as_normal_fault
    li      t3, 15
    beq     t1, t3, handle_as_normal_fault
    
    # 如果不是页错误，也不是 ecall，执行你原有的逻辑
    j       original_logic

handle_as_normal_fault:
    # 页错误同样需要计算指令长度并跳过，复用原有的长度解码逻辑
    j       original_logic

original_logic:
    # 你的原有逻辑：指令长度解码
    li      t2, 2                
    li      t3, 2                
    beq     t1, t3, illegal_len
    li      t3, 1                
    beq     t1, t3, instr_fault_len
    li      t3, 12               
    beq     t1, t3, instr_fault_len

    lhu     t4, 0(t0)
    j       decode_length

other_len:
    lhu     t4, 0(t0)
    j       decode_length

illegal_len:
    beqz    t4, other_len
    j       decode_length
    
instr_fault_len:
    li      t2, 4
    j       update_mepc

decode_length:
    andi    t4, t4, 3
    li      t3, 3
    bne     t4, t3, compressed_len
    li      t2, 4
    j       update_mepc

compressed_len:
    li      t2, 2

update_mepc:
    add     t0, t0, t2
    csrw    mepc, t0
    csrw    mcause, x0
    csrw    mtval, x0
    mret

    .section .data
    .balign 4096
pgtbl:
    .zero 4096

    .section .tohost,"aw",@progbits
    .align  6
    .globl  tohost
    .globl  fromhost
tohost:
    .dword  0
fromhost:
    .dword  0