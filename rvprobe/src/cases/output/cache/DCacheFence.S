    .section .text
    .globl _start
_start:
    la      x5, buf
    li      x10, 0xCAFEBABE
    sw x10, 0(x5)
    fence x0, x0
    lw x11, 0(x5)
    la      x6, buf2
    li      x10, 0xDEADCAFE
    sw x10, 0(x6)
    fence.tso x0, x0
    lw x12, 0(x6)
exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
buf:
    .zero 64
    .balign 64
buf2:
    .zero 64
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0