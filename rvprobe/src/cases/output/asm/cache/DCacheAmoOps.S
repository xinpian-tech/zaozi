    .section ".text"
    .globl _start
_start:
    la x5, amo_buf
    li x4, 0x64
    sw x4, 0(x5)
    addi x6, x0, 5
    amoadd.w x1, x6, (x5)
    li x7, 0xc8
    amoswap.w x2, x7, (x5)
    lw x3, 0(x5)
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
    .balign 64
amo_buf:
    .zero 64