// SPDX-License-Identifier: Apache-2.0
#include <errno.h>
#include <stdio.h>
#include <mlir-c/IR.h>

struct BytecodeOutput {
  FILE *stream;
  int error;
};

static void write_chunk(MlirStringRef bytes, void *state) {
  struct BytecodeOutput *out = state;
  if (!out->error && bytes.length &&
      fwrite(bytes.data, 1, bytes.length, out->stream) != bytes.length)
    out->error = errno ? errno : EIO;
}

int zaozi_write_bytecode_file(const MlirOperation *op, const char *path) {
  FILE *stream = fopen(path, "wb");
  if (!stream) return errno;
  struct BytecodeOutput out = {stream, 0};
  mlirOperationWriteBytecode(*op, write_chunk, &out);
  if (fclose(stream) && !out.error) out.error = errno ? errno : EIO;
  return out.error;
}
