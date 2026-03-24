    .section ".text"
    .globl _start
_start:
    la x5, buf
    li x22, 0x1000
    add x6, x5, x0
    add x7, x6, x22
    add x28, x7, x22
    add x29, x28, x22
    add x30, x29, x22
    lw x10, 0(x6)
    lw x10, 0(x7)
    lw x10, 0(x28)
    lw x10, 0(x29)
    lw x10, 0(x30)
    rdcycle x14
    lw x11, 0(x6)
    rdcycle x15
    sub x16, x15, x14
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
buf:
    .zero 32768
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0