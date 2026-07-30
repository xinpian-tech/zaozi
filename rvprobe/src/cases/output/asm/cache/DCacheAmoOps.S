    .section ".text"
    .globl _start
_start:
    la x1, amo_buf
    li x4, 0x64
    sw x4, 0(x1)
    addi x3, x0, 5
    amoadd.w x9, x3, (x1)
    li x8, 0xc8
    amoswap.w x2, x8, (x1)
    lw x7, 0(x1)
exit:
    li x5, 0x0
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin:
    j spin

fail:
    li x5, 0x1
    slli x5, x5, 1
    ori x5, x5, 1
    la x6, tohost
    sd x5, 0(x6)
spin_fail:
    j spin_fail

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
    .balign 64
amo_buf:
    .zero 64