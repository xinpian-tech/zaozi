    .section ".text"
    .globl _start
_start:
    la x5, trap_handler
    csrrw x0, mtvec, x5
    j trap_handler_skip

    .align 2
trap_handler:
    csrrs x5, mepc, x0
    csrrs x6, mcause, x0
    csrrs x29, mtval, x0
    slli x30, x6, 1
    srli x6, x30, 1
    addi x28, x0, 9
    bne x6, x28, check_page_fault

    li x7, 0x1800
    csrrs x0, mstatus, x7
    addi x7, x0, 4
    j update_mepc

check_page_fault:
    addi x28, x0, 13
    beq x6, x28, handle_as_normal_fault

    addi x28, x0, 15
    beq x6, x28, handle_as_normal_fault

    j original_logic

handle_as_normal_fault:
    j original_logic

original_logic:
    addi x7, x0, 2
    addi x28, x0, 2
    beq x6, x28, illegal_len

    addi x28, x0, 1
    beq x6, x28, instr_fault_len

    addi x28, x0, 12
    beq x6, x28, instr_fault_len

    lhu x29, 0(x5)
    j decode_length

other_len:
    lhu x29, 0(x5)
    j decode_length

illegal_len:
    beqz x29, other_len

    j decode_length

instr_fault_len:
    addi x7, x0, 4
    j update_mepc

decode_length:
    andi x29, x29, 3
    addi x28, x0, 3
    bne x29, x28, compressed_len

    addi x7, x0, 4
    j update_mepc

compressed_len:
    addi x7, x0, 2
update_mepc:
    add x5, x5, x7
    csrrw x0, mepc, x5
    csrrw x0, mcause, x0
    csrrw x0, mtval, x0
    mret
trap_handler_skip:
    addi x5, x0, -1
    csrrw x0, pmpaddr0, x5
    addi x5, x0, 31
    csrrw x0, pmpcfg0, x5
    la x5, pgtbl
    li x6, 0x200000cf
    sd x6, 16(x5)
    la x5, pgtbl
    addi x7, x0, 8
    slli x7, x7, 60
    srli x6, x5, 12
    or x7, x7, x6
    csrrw x0, satp, x7
    sfence.vma x0, x0
    csrrw x0, medeleg, x0
    csrrw x0, mideleg, x0
    li x5, 0xffffffffffffe7ff
    csrrs x6, mstatus, x0
    and x6, x6, x5
    li x7, 0x800
    or x6, x6, x7
    csrrw x0, mstatus, x6
    la x5, s_code
    csrrw x0, mepc, x5
    mret
s_code:
    addi x20, x0, 1
    ecall
    addi x20, x20, 1
    addi x21, x0, 2
    bne x20, x21, fail

    j exit

fail:
    li x5, 0x1
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin_fail:
    j spin_fail

exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0
    .section .data
    .balign 4096
pgtbl:
    .zero 4096