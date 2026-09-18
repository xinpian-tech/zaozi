"""Native cover selection and safe Tcl words; no property rewriting."""
import re

COVER = re.compile(r"(\w+):((?:[^\n]*\n)?\s*)cover property \((.*?)\);", re.S)


def select_cover(sv, labels, label):
    matches = list(COVER.finditer(sv))
    found = [m[1] for m in matches]
    if len(set(found)) != len(found) or set(found) != set(labels) or label not in found:
        raise ValueError("UT cover labels differ from prepared job")
    return COVER.sub(lambda m: m[0]
                         if m[1] == label else "", sv)


def tcl_word(value):
    value = str(value)
    if any(c in value for c in "{}\n\r\\"):
        raise ValueError("unsupported Tcl path or identifier")
    return "{" + value + "}"
