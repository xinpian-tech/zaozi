// SPDX-License-Identifier: Apache-2.0
#include <circt-c/Dialect/HW.h>

extern void zaozi_graph_visit(HWInstanceGraphNode *, void *);
static void visit(HWInstanceGraphNode node, void *state) {
  zaozi_graph_visit(&node, state);
}
HWInstanceGraphNodeCallback zaozi_graph_callback(void) { return visit; }
