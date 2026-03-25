    .section .text
    .globl _start
_start:
    la      x5, array
    li      x20, 256
    li      x22, 64
loop_stride:
    lw x10, 0(x5)
    add x5, x5, x22
    addi x20, x20, -1
    bnez x20, loop_stride

exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
array:
    .zero 16384
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0