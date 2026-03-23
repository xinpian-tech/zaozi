    .section .text
    .globl _start
_start:
    la      x5, big_array
    li      x20, 8192
    rdcycle x14
loop1:
    lw x10, 0(x5)
    addi x5, x5, 4
    addi x20, x20, -1
    bnez x20, loop1

    rdcycle x15
    sub x16, x15, x14
    la      x5, big_array
    li      x20, 8192
    rdcycle x14
loop2:
    lw x10, 0(x5)
    addi x5, x5, 4
    addi x20, x20, -1
    bnez x20, loop2

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
big_array:
    .zero 65536
    .section .tohost,"aw",@progbits
    .align 6
    .globl tohost
    .globl fromhost
tohost:
    .dword 0x0
fromhost:
    .dword 0x0