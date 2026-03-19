    .section .text
    .globl _start
_start:
    la      x5, array
    li      x20, 1024
loop_seq:
    lw x10, 0(x5)
    addi x5, x5, 4
    addi x20, x20, -1
    bnez x20, loop_seq

exit:
    addi x5, x0, 1
    la      x6, tohost
    sd x5, 0(x6)
spin:
    j spin

    .section .data
    .balign 64
array:
    .zero 4096
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0