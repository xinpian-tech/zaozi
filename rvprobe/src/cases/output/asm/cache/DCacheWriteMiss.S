    .section ".text"
    .globl _start
_start:
    la x3, buf2
    li x2, 0x55667788
    rdcycle x14
    sw x2, 0(x3)
    rdcycle x15
    sub x16, x15, x14
    rdcycle x14
    lw x1, 0(x3)
    rdcycle x15
    sub x17, x15, x14
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
buf2:
    .zero 256