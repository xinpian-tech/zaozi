OUTPUT_ARCH(riscv)
ENTRY(_start)

PHDRS {
  text PT_LOAD FLAGS(5);
  data PT_LOAD FLAGS(6);
}

SECTIONS {
  . = 0x80000000;
  .text : ALIGN(4) {
    *(.text .text.*)
  } :text

  .rodata : ALIGN(16) {
    *(.rodata .rodata.*)
  } :text

  .data : ALIGN(16) {
    *(.data .data.*)
  } :data

  .tohost : ALIGN(8) {
    *(.tohost)
  } :data

  .bss (NOLOAD) : ALIGN(16) {
    *(.bss .bss.*)
    *(COMMON)
  } :data
}
