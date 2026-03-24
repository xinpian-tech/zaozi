    .section ".text"
    .globl _start
_start:
    la x5, buf
    li x10, 0x11223344
    sw x10, 0(x5)
    addi x11, x0, 170
    sb x11, 1(x5)
    li x12, 0xbbbb
    sh x12, 2(x5)
    lw x13, 0(x5)
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
    .zero 64
    .section ".tohost","aw",@progbits
    .align 6
    .globl tohost
tohost:
    .dword 0x0
    .align 6
    .globl fromhost
fromhost:
    .dword 0x0