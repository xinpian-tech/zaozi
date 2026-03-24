    .section ".text"
    .globl _start
_start:
    la x5, buf
    li x10, 0xcafebabe
    sw x10, 0(x5)
    fence rw, rw
    lw x11, 0(x5)
    la x6, buf2
    li x10, 0xdeadcafe
    sw x10, 0(x6)
    fence.tso
    lw x12, 0(x6)
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
    .balign 64
buf2:
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