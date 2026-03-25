    .section ".text"
    .globl _start
_start:
    la x4, buf
    rdcycle x14
    lw x1, 0(x4)
    rdcycle x15
    sub x16, x15, x14
    rdcycle x14
    lw x2, 0(x4)
    rdcycle x15
    sub x17, x15, x14
    lw x3, 0(x4)
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
buf:
    .word 0x12345678
    .zero 60