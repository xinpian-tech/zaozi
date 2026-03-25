    .section .text
    .globl _start
_start:
    la      x5, buf
    li      x22, 4096
    add x6, x5, x0
    add x7, x5, x22
    add x28, x7, x22
    lw x10, 0(x6)
    lw x10, 0(x7)
    lw x10, 0(x6)
    lw x10, 0(x28)
    rdcycle x14
    lw x11, 0(x7)
    rdcycle x15
    sub x16, x15, x14
    rdcycle x14
    lw x12, 0(x6)
    rdcycle x15
    sub x17, x15, x14
exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
buf:
    .zero 32768
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0