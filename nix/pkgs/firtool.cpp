// SPDX-License-Identifier: Apache-2.0
#include <circt-c/Firtool/Firtool.h>
#include <circt/Firtool/Firtool.h>

// The pinned CIRCT implementation takes the C++ enum, producing a mangled
// overload instead of the C symbol advertised by circt-c/Firtool/Firtool.h.
extern "C" void circtFirtoolOptionsSetVerificationFlavor(
    CirctFirtoolFirtoolOptions options, CirctFirtoolVerificationFlavor flavor) {
  auto *firtool = static_cast<circt::firtool::FirtoolOptions *>(options.ptr);
  firtool->setVerificationFlavor(static_cast<circt::firrtl::VerificationFlavor>(flavor));
}
