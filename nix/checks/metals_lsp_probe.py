#!/usr/bin/env python3
# Minimal headless LSP client that drives a Metals server over stdio to obtain a
# textDocument/completion, used to prove which presentation compiler Metals loaded.
#
#   metals_lsp_probe.py <workspace-dir> <scala-file> <line> <char>
#
# Prints (stdout) the JSON list of completion item labels. Exits non-zero if Metals
# never produces completions (e.g. the PC could not be resolved offline). Diagnostic
# server log lines (window/logMessage, $/progress) go to stderr.
import json, os, subprocess, sys, threading, time, queue

WS, FILE = sys.argv[1], sys.argv[2]
# Two argv shapes:
#   <ws> <file> <line> <char> [completion|hover|definition]   -- one request
#   <ws> <file> BATCH <name:mode:line:char> ...               -- many requests, one session
if len(sys.argv) > 3 and sys.argv[3] == "BATCH":
    MODE, SPECS, LINE, CHAR = "batch", sys.argv[4:], 0, 0
else:
    LINE, CHAR = int(sys.argv[3]), int(sys.argv[4])
    MODE, SPECS = (sys.argv[5] if len(sys.argv) > 5 else "completion"), []
METALS = os.environ["METALS_BIN"]
DEADLINE = time.time() + int(os.environ.get("PROBE_TIMEOUT", "300"))

_errlog = os.environ.get("METALS_STDERR")
_errfd = open(_errlog, "wb") if _errlog else sys.stderr.buffer
proc = subprocess.Popen([METALS], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=_errfd, bufsize=0)

_lock = threading.Lock()
_id = 0
_resp = {}                  # id -> response message
_resp_ev = {}               # id -> threading.Event
_notes = queue.Queue()      # server notifications (method, params)

def _send(msg):
    data = json.dumps(msg).encode("utf-8")
    hdr = f"Content-Length: {len(data)}\r\n\r\n".encode("ascii")
    with _lock:
        proc.stdin.write(hdr + data)
        proc.stdin.flush()

def request(method, params):
    global _id
    with _lock:
        _id += 1
        rid = _id
    ev = threading.Event()
    _resp_ev[rid] = ev
    _send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
    while not ev.wait(0.2):
        if time.time() > DEADLINE:
            raise TimeoutError(f"no response to {method}")
    return _resp.pop(rid)

def notify(method, params):
    _send({"jsonrpc": "2.0", "method": method, "params": params})

def _read_loop():
    f = proc.stdout
    while True:
        # read headers
        headers = {}
        line = f.readline()
        if not line:
            return
        while line not in (b"\r\n", b"\n", b""):
            k, _, v = line.decode("latin1").partition(":")
            headers[k.strip().lower()] = v.strip()
            line = f.readline()
        n = int(headers.get("content-length", "0"))
        body = b""
        while len(body) < n:
            chunk = f.read(n - len(body))
            if not chunk:
                return
            body += chunk
        try:
            msg = json.loads(body.decode("utf-8"))
        except Exception:
            continue
        if "id" in msg and ("result" in msg or "error" in msg):
            rid = msg["id"]
            _resp[rid] = msg
            ev = _resp_ev.get(rid)
            if ev:
                ev.set()
        elif "id" in msg and "method" in msg:
            # server -> client request: answer so Metals can proceed headlessly
            _handle_server_request(msg)
        elif "method" in msg:
            _notes.put((msg["method"], msg.get("params")))

def _handle_server_request(msg):
    m = msg["method"]
    result = None
    if m == "window/showMessageRequest":
        # pick the "Import build" / first action so the build gets imported
        actions = (msg.get("params") or {}).get("actions") or []
        pick = None
        for a in actions:
            if "import" in a.get("title", "").lower():
                pick = a
        result = pick or (actions[0] if actions else None)
    elif m in ("window/workDoneProgress/create", "client/registerCapability",
               "client/unregisterCapability", "workspace/configuration"):
        result = [] if m == "workspace/configuration" else None
    elif m == "workspace/applyEdit":
        result = {"applied": True}
    _send({"jsonrpc": "2.0", "id": msg["id"], "result": result})

def log_notes():
    # drain notifications to stderr for diagnosis
    while not _notes.empty():
        method, params = _notes.get()
        if method in ("window/logMessage", "window/showMessage"):
            txt = (params or {}).get("message", "")
            sys.stderr.write(f"[metals] {txt}\n")

threading.Thread(target=_read_loop, daemon=True).start()

root_uri = "file://" + os.path.abspath(WS)
request("initialize", {
    "processId": os.getpid(),
    "rootUri": root_uri,
    "capabilities": {"textDocument": {"completion": {"completionItem": {"snippetSupport": False}}}},
    "initializationOptions": {
        "isHttpEnabled": False,
        "compilerOptions": {"snippetAutoIndent": False},
        # auto-import the build with no popups; never reach the network
        "statusBarProvider": "log-message",
        "didFocusProvider": True,
    },
})
notify("initialized", {})
# Force auto-import + offline behaviour via configuration.
notify("workspace/didChangeConfiguration", {"settings": {"metals": {
    "autoImportBuild": "all",
    "fallbackScalaVersion": "3.8.3",
}}})

uri = "file://" + os.path.abspath(FILE)
text = open(FILE).read()
notify("textDocument/didOpen", {"textDocument": {
    "uri": uri, "languageId": "scala", "version": 1, "text": text}})

def comp_items(ln, ch):
    try:
        r = request("textDocument/completion", {"textDocument": {"uri": uri}, "position": {"line": ln, "character": ch}})
    except TimeoutError:
        return None
    res = r.get("result")
    return (res.get("items") if isinstance(res, dict) else res) or []

def wait_ready(ln, ch):
    """Wait until completion at (ln,ch) yields items — i.e. the build is imported and the PC is up."""
    while time.time() < DEADLINE:
        log_notes()
        items = comp_items(ln, ch)
        if items:
            return True
        time.sleep(2)
    return False

def req_result(method, ln, ch):
    """One request; returns its result (possibly empty/None — an empty result is a valid answer
    for hover/definition once the PC is up). Short retry to absorb a transient empty."""
    deadline2 = min(DEADLINE, time.time() + 15)
    last = None
    while time.time() < deadline2:
        log_notes()
        try:
            r = request(method, {"textDocument": {"uri": uri}, "position": {"line": ln, "character": ch}})
        except TimeoutError:
            return None
        last = r.get("result")
        if last:
            return last
        time.sleep(2)
    return last

def fmt_hover(res):
    c = res.get("contents") if isinstance(res, dict) else None
    if isinstance(c, dict):
        return c.get("value", "")
    if isinstance(c, list):
        return " ".join((x.get("value", "") if isinstance(x, dict) else str(x)) for x in c)
    return str(c) if c is not None else ""

def fmt_def(res):
    locs = res if isinstance(res, list) else ([res] if res else [])
    out = []
    for l in locs:
        if not isinstance(l, dict):
            continue
        rng = l.get("range") or l.get("targetSelectionRange") or l.get("targetRange") or {}
        out.append({"uri": l.get("uri") or l.get("targetUri"), "line": (rng.get("start") or {}).get("line")})
    return out

def one(mode, ln, ch):
    if mode == "completion":
        items = []
        d2 = min(DEADLINE, time.time() + 30)
        while time.time() < d2:
            items = comp_items(ln, ch) or []
            if items:
                break
            time.sleep(1)
        return [it.get("label", "").strip() for it in items]
    res = req_result("textDocument/" + mode, ln, ch)
    return fmt_hover(res) if mode == "hover" else fmt_def(res)

def shutdown():
    log_notes()
    try:
        request("shutdown", {}); notify("exit", {})
    except Exception:
        pass
    proc.terminate()

if MODE == "completion":
    out, ok = [], False
    while time.time() < DEADLINE:
        log_notes()
        items = comp_items(LINE, CHAR)
        if items:
            out, ok = [it.get("label", "").strip() for it in items], True
            break
        time.sleep(2)
    shutdown()
    if not ok:
        sys.stderr.write("no completions obtained\n"); print(json.dumps([])); sys.exit(3)
    print(json.dumps(out)); sys.exit(0)

elif MODE == "batch":
    # Readiness via completion at the first spec's position, then run every request once.
    p0 = SPECS[0].split(":")
    ready = wait_ready(int(p0[2]), int(p0[3]))
    result = {}
    if ready:
        for spec in SPECS:
            name, mode, ln, ch = spec.split(":")
            result[name] = one(mode, int(ln), int(ch))
    shutdown()
    if not ready:
        sys.stderr.write("PC not ready (batch)\n"); print(json.dumps({})); sys.exit(3)
    print(json.dumps(result)); sys.exit(0)

else:  # single hover | definition: exit non-zero ONLY if the PC never came up
    ready = wait_ready(LINE, CHAR)
    out = one(MODE, LINE, CHAR) if ready else ("" if MODE == "hover" else [])
    shutdown()
    if not ready:
        sys.stderr.write(f"PC not ready ({MODE})\n"); print("" if MODE == "hover" else json.dumps([])); sys.exit(3)
    print(out if MODE == "hover" else json.dumps(out)); sys.exit(0)
