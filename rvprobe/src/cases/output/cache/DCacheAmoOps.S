    .section .text
    .globl _start
_start:
    la      x5, amo_buf
    li      x10, 100
    sw x10, 0(x5)
    addi x11, x0, 5
    amoadd.w x12, x5, x11
    li      x13, 200
    amoswap.w x14, x5, x13
    lw x15, 0(x5)
exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
amo_buf:
    .zero 64
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0