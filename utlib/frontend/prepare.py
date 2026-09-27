"""Select and prepare the DPI frontend used by a unit test."""


def prepare(name, schema, driver, stimulus, build_dir):
    if name == "cpp":
        from frontend.cpp.prepare import prepare as prepare_cpp

        return prepare_cpp(schema, driver, stimulus, build_dir)
    if name == "cpython":
        from frontend.cpython.prepare import prepare as prepare_cpython

        return prepare_cpython(schema, driver, stimulus, build_dir)
    raise ValueError(f"unsupported frontend: {name}")
