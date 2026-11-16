# SPDX-License-Identifier: Apache-2.0
"""Validate Python DPI callbacks without running the simulator."""

import os
from collections.abc import Callable
from pathlib import Path
from typing import TypeVar

from .codegen import functions
from .data import DpiFunction, json_value
from .runtime import Callback, ErrorHandler, register

_Callback = TypeVar("_Callback", bound=Callback)


class PreviewFFI:
    functions: dict[str, DpiFunction]

    def __init__(self, interface: object) -> None:
        self.functions = {
            function["function"]: function for function in functions(interface)
        }

    def def_extern(
        self, *, name: str, onerror: ErrorHandler
    ) -> Callable[[_Callback], _Callback]:
        _ = onerror  # Preview errors propagate to the launcher.

        def bind(callback: _Callback) -> _Callback:
            parameters = [
                arg
                for arg in self.functions[name]["arguments"]
                if arg["direction"] != "return"
            ]
            if all(arg["direction"] == "out" for arg in parameters):
                # With no observation inputs, exercise the callback once in this
                # separate process to validate its output names and value ranges.
                _ = callback(*([0] for _ in parameters))
            return callback

        return bind


if __name__ == "__main__":
    interface = json_value(Path(os.environ["ZAOZI_TB_INTERFACE"]).read_text())
    register(PreviewFFI(interface))
