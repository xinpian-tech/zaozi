import os
from typing import override

import lit
from lit.formats import ShTest


class ScalaCliTest(ShTest):
    def __init__(
        self, execute_external=False, extra_substitutions=None, preamble_commands=None
    ):
        super().__init__()
        self.execute_external = execute_external
        self.extra_substitutions = (
            extra_substitutions if extra_substitutions is not None else []
        )
        self.preamble_commands = (
            preamble_commands if preamble_commands is not None else []
        )

    @override
    def getTestsForPath(self, testSuite, path_in_suite, litConfig, localConfig):
        filename = path_in_suite[-1]

        # Ignore dot files, excluded tests, and paths with a dir starting with '.'.
        if filename.startswith(".") or filename in localConfig.excludes:
            return
        if any(p.startswith(".") for p in path_in_suite[:-1]):
            return

        _, ext = os.path.splitext(filename)
        if ext in localConfig.suffixes:
            yield lit.Test.Test(testSuite, path_in_suite, localConfig)
