    .section ".text"
    .globl _start
_start:
    la x5, buf2
    li x11, 0x55667788
    rdcycle x14
    sw x11, 0(x5)
    rdcycle x15
    sub x16, x15, x14
    rdcycle x14
    lw x12, 0(x5)
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

    .section .data
    .balign 64
buf2:
    .zero 256
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0