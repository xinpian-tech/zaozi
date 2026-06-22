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

WS, FILE, LINE, CHAR = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
MODE = sys.argv[5] if len(sys.argv) > 5 else "completion"  # completion | hover | definition
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

def poll_completion():
    """Poll completion until Metals has imported the build and started the PC."""
    while time.time() < DEADLINE:
        log_notes()
        try:
            r = request("textDocument/completion", {
                "textDocument": {"uri": uri},
                "position": {"line": LINE, "character": CHAR},
            })
        except TimeoutError:
            return None
        res = r.get("result")
        items = res.get("items") if isinstance(res, dict) else res
        if items:
            return items
        time.sleep(2)
    return None

def poll(method):
    """Poll a hover/definition request (after the PC is up) until it returns a non-empty result."""
    deadline2 = min(DEADLINE, time.time() + 150)
    while time.time() < deadline2:
        log_notes()
        try:
            r = request(method, {"textDocument": {"uri": uri}, "position": {"line": LINE, "character": CHAR}})
        except TimeoutError:
            return None
        if r.get("result"):
            return r["result"]
        time.sleep(2)
    return None

out, ok = None, False
if MODE == "completion":
    items = poll_completion() or []
    out = [it.get("label", "").strip() for it in items]
    ok = bool(out)
else:
    poll_completion()                       # cheapest readiness signal: wait for the PC
    res = poll("textDocument/" + MODE)
    if MODE == "hover":
        text = ""
        c = res.get("contents") if isinstance(res, dict) else None
        if isinstance(c, dict):
            text = c.get("value", "")
        elif isinstance(c, list):
            text = " ".join((x.get("value", "") if isinstance(x, dict) else str(x)) for x in c)
        elif c is not None:
            text = str(c)
        out, ok = text, bool(text)
    else:  # definition: Location | Location[] | LocationLink[]
        locs = res if isinstance(res, list) else ([res] if res else [])
        norm = []
        for l in locs:
            if not isinstance(l, dict):
                continue
            rng = l.get("range") or l.get("targetSelectionRange") or l.get("targetRange") or {}
            norm.append({"uri": l.get("uri") or l.get("targetUri"),
                         "line": (rng.get("start") or {}).get("line")})
        out, ok = norm, bool(norm)

log_notes()
try:
    request("shutdown", {})
    notify("exit", {})
except Exception:
    pass
proc.terminate()

if not ok:
    sys.stderr.write(f"no {MODE} result obtained\n")
    print("" if MODE == "hover" else json.dumps([]))
    sys.exit(3)
print(out if MODE == "hover" else json.dumps(out))
