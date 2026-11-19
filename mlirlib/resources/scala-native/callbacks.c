// SPDX-License-Identifier: Apache-2.0
#include <mlir-c/Support.h>

extern void zaozi_string_chunk(const char *, size_t, void *);

static void string_chunk(MlirStringRef chunk, void *state) {
  zaozi_string_chunk(chunk.data, chunk.length, state);
}

MlirStringCallback zaozi_string_callback(void) { return string_chunk; }

#include <mlir-c/Diagnostics.h>
#include <mlir-c/IR.h>
#include <mlir-c/Pass.h>

extern bool zaozi_diagnostic(MlirDiagnostic *, void *);
extern int zaozi_walk(MlirOperation *, void *);
extern void zaozi_release(void *);

static MlirLogicalResult diagnostic(MlirDiagnostic message, void *state) {
  return zaozi_diagnostic(&message, state) ? mlirLogicalResultSuccess()
                                           : mlirLogicalResultFailure();
}
static MlirWalkResult walk(MlirOperation operation, void *state) {
  return (MlirWalkResult)zaozi_walk(&operation, state);
}
MlirDiagnosticHandler zaozi_diagnostic_callback(void) { return diagnostic; }
MlirOperationWalkCallback zaozi_walk_callback(void) { return walk; }
typedef void (*DeleteCallback)(void *);
DeleteCallback zaozi_delete_callback(void) { return zaozi_release; }

extern void zaozi_pass_construct(void *);
extern void zaozi_pass_destruct(void *);
extern void zaozi_pass_clone(void *);
extern bool zaozi_pass_initialize(MlirContext *, void *);
extern void zaozi_pass_run(MlirOperation *, MlirExternalPass *, void *);

static void *clone_pass(void *state) {
  zaozi_pass_clone(state);
  return state;
}
static MlirLogicalResult initialize_pass(MlirContext context, void *state) {
  return zaozi_pass_initialize(&context, state) ? mlirLogicalResultSuccess()
                                                 : mlirLogicalResultFailure();
}
static void run_pass(MlirOperation op, MlirExternalPass pass, void *state) {
  zaozi_pass_run(&op, &pass, state);
}
void zaozi_pass_callbacks(MlirExternalPassCallbacks *out, bool initialize) {
  *out = (MlirExternalPassCallbacks){
    .construct = zaozi_pass_construct, .destruct = zaozi_pass_destruct,
    .initialize = initialize ? initialize_pass : NULL,
    .clone = clone_pass, .run = run_pass
  };
}
