"""Frozen RVProbe skill context and private compilation repair journal."""
import difflib
import hashlib
import json
import os
from pathlib import Path

from run_records import save, model_usage, response_metadata
from task_context import TOOLS, MAX_MODEL_CALLS, MAX_TOOL_CALLS
from evidence_packet import packet, request_messages, POLICY as DIALOGUE_POLICY
from request_deadline import POLICY as REQUEST_DEADLINE_POLICY
from provider_failure import incomplete_response
from repair_policy import model_repair_allowed

SKILL = Path(__file__).resolve().parent.parent / "rvprobe-skill.md"
SKILL_PROTOCOL = "frozen-inline-ltl-skill-v10"


def compact_skill_text(content):
    """Remove metadata/presentation only; retain every API row and example."""
    import re
    content=re.sub(r'\A---\n.*?\n---\n', '', content, count=1, flags=re.S)
    lines=[]
    for line in content.splitlines():
        stripped=line.strip()
        if stripped.startswith('```'):
            continue
        if re.fullmatch(r'\|[\s|:-]+\|',stripped):
            continue
        if not stripped and (not lines or not lines[-1]):
            continue
        lines.append(line)
    compact='\n'.join(lines).strip()
    return compact


def snapshot():
    content = SKILL.read_text()
    return {"name": SKILL.name, "sha256": hashlib.sha256(content.encode()).hexdigest(), "content": content}


def load_snapshot(path):
    value = json.loads(Path(path).read_text())
    if (set(value) != {"name", "sha256", "content"} or value["name"] != SKILL.name
            or not isinstance(value["content"], str)
            or hashlib.sha256(value["content"].encode()).hexdigest() != value["sha256"]):
        raise ValueError("invalid frozen skill snapshot")
    return value


def invoke_with_skill(prompt, model, temperature, timeout, send, records, directory, request_number, skill, context=None, *, max_tokens=None, reasoning_effort=None, dialogue_policy='staged', evidence_steps=None, evidence_tools=None, retrieval_max_tokens=None, retrieval_reasoning_effort=None):
    from rvprobe_model_options import REASONING_EFFORTS
    if reasoning_effort is not None and reasoning_effort not in REASONING_EFFORTS:
        raise ValueError('unsupported RVProbe reasoning effort')
    if max_tokens is not None:
        from rvprobe_model_options import token_limit
        max_tokens=token_limit(max_tokens)
    if retrieval_max_tokens is not None:
        from rvprobe_model_options import token_limit
        retrieval_max_tokens=token_limit(retrieval_max_tokens)
    if retrieval_reasoning_effort is not None and retrieval_reasoning_effort not in REASONING_EFFORTS:
        raise ValueError('unsupported RVProbe retrieval reasoning effort')
    if dialogue_policy == 'incremental':
        from incremental_dialogue import invoke
        return invoke(prompt, model, temperature, timeout, send, records, directory,
                      request_number, skill, context, max_tokens=max_tokens,
                      reasoning_effort=reasoning_effort, evidence_steps=evidence_steps,
                      evidence_tools=evidence_tools, retrieval_max_tokens=retrieval_max_tokens,
                      retrieval_reasoning_effort=retrieval_reasoning_effort)
    if dialogue_policy != 'staged':
        raise ValueError('unsupported dialogue policy')
    # Plain context messages, never fabricated assistant/tool exchanges.
    messages = []
    if skill is not None:
        # Hash/provenance belongs in artifacts, not in a JSON-escaped model instruction.
        messages.append({"role": "user", "content": "RVProbe LTL API and usage (compact):\n" + compact_skill_text(skill["content"])})
        save(directory / "skill-context.json", {"protocol": SKILL_PROTOCOL, **skill})
    messages.append({"role": "user", "content": prompt})
    base_messages = list(messages)
    observations = []
    if context is not None:
        evidence_steps = evidence_steps if evidence_steps is not None else getattr(context, 'evidence_steps', MAX_MODEL_CALLS - 1)
        evidence_tools = evidence_tools if evidence_tools is not None else getattr(context, 'evidence_tools', MAX_TOOL_CALLS)
    else:
        evidence_steps, evidence_tools = 0, 0
    # Tests and bounded callers may temporarily lower global quotas; never let
    # a saved context silently exceed the active process quota.
    if context is not None:
        evidence_steps = min(evidence_steps, MAX_MODEL_CALLS - 1)
        evidence_tools = min(evidence_tools, MAX_TOOL_CALLS)
    if context is not None and (type(evidence_steps) is not int or not 0 <= evidence_steps < MAX_MODEL_CALLS or
                                type(evidence_tools) is not int or not 1 <= evidence_tools <= MAX_TOOL_CALLS):
        raise ValueError('invalid evidence dialogue budget')
    if context is not None:
        evidence=context.initial_evidence()
        save(directory/'initial-evidence.json',evidence)
    exposed_tools=context.tools if context is not None else []
    calls = []
    tool_count = 0
    retrieving = False
    authoring = False
    for step in range(MAX_MODEL_CALLS if context is not None else 1):
        stop = os.environ.get('RVPROBE_STOP_NEW_MODEL_REQUESTS')
        if context is not None and stop and Path(stop).exists():
            raise RuntimeError('campaign_gate_stop: no new model requests after pilot health failure; prior usage retained')
        final_slot = context is not None and (step == MAX_MODEL_CALLS - 1 or step >= evidence_steps or tool_count >= evidence_tools)
        final_slot = final_slot or authoring
        if context is not None:
            # A fresh inference request, not a truncated tool conversation. There
            # are no orphan tool replies or stripped assistant protocol fields.
            current = packet(context, evidence, observations)
            save(directory / f'evidence-{request_number}-{step}.json', current)
            messages = request_messages(base_messages, current, MAX_MODEL_CALLS-step-1,
                                        max(0,evidence_tools-tool_count), final_slot)
        request_max_tokens = max_tokens if final_slot or retrieval_max_tokens is None else retrieval_max_tokens
        request_reasoning_effort = reasoning_effort if final_slot or retrieval_reasoning_effort is None else retrieval_reasoning_effort
        payload = {"model": model, "temperature": temperature, "messages": list(messages)}
        if request_max_tokens is not None:payload['max_tokens']=request_max_tokens
        if request_reasoning_effort is not None:payload['reasoning_effort']=request_reasoning_effort
        if context is not None:
            payload.update(tools=exposed_tools, tool_choice="none" if final_slot else "auto")
        with records.phase("model-request", attempt=directory.name, request=request_number,
                           tool_step=step, requested_model=model,
                           requested_max_tokens=request_max_tokens,
                           requested_reasoning_effort=request_reasoning_effort,
                           request_timeout_seconds=timeout, request_timeout_policy=REQUEST_DEADLINE_POLICY,
                           request_mode=('evidence-or-ltl-v2' if retrieving and not final_slot else
                               'ltl-authoring-v1' if final_slot else
                               context.record().get('request_mode','authoring') if context is not None else 'authoring'),
                           tool_budget_used=tool_count,
                           dialogue_policy=DIALOGUE_POLICY if context is not None else None,
                           history_reasoning_characters=sum(len(m.get('reasoning_content') or '') for m in messages),
                           input_characters=sum(len(m.get("content") or "") for m in messages),
                           payload_characters=len(json.dumps(payload)),
                           payload_sha256=hashlib.sha256(json.dumps(payload).encode()).hexdigest()) as event:
            result = send(payload, timeout)
            event.update(**model_usage(result), **response_metadata(result),
                         reported_model=result.get("model"), response_id=result.get("id"))
        calls.append(dict(event))
        save(directory / f"dialogue-{request_number}.json", {"calls": calls, "skill": skill, "skill_protocol": SKILL_PROTOCOL if skill else None})
        message = result["choices"][0]["message"]
        if message.get("tool_calls") and context is not None:
            if final_slot:
                save(directory / f"unexpected-final-tools-{request_number}.json", {
                    "tool_names":[c.get('function',{}).get('name') for c in message['tool_calls']], **event})
                raise RuntimeError('model dialogue call budget exhausted: provider requested tools despite tool_choice=none; cost preserved')
            if event["response_status"] in ("truncated", "filtered"):
                save(directory / f"incomplete-response-{request_number}.json", {
                    **event, "stage": "task-tools", "policy": "stop-without-automatic-regeneration-v1"})
                raise incomplete_response(event, "incomplete task tool response; cost preserved; no automatic regeneration")
            requests = message["tool_calls"]
            if not isinstance(requests, list):
                raise ValueError("invalid task tool-call list; cost preserved")
            for ordinal, request in enumerate(requests):
                if request.get("type") != "function" or not request.get("id"):
                    raise ValueError("invalid read-only task tool call")
                function = request.get("function", {})
                if function.get("name") not in {t["function"]["name"] for t in exposed_tools}:
                    raise ValueError("only allowlisted read-only task tools are permitted")
                arguments = None
                try:
                    arguments = json.loads(function.get("arguments", "null"))
                except (ValueError,TypeError):
                    pass
                cost=context.call_cost(function['name'],arguments)
                if authoring or tool_count+cost>evidence_tools:
                    authoring = True
                    result_value = {'error':'Evidence budget closed; this call was NOT executed. '
                                    'Use existing evidence and output raw LTL (or STOP).'}
                    save(directory / f'tool-budget-refusal-{request_number}-{step}-{ordinal}.json',
                         {'tool_call':request, 'used':tool_count, 'requested_cost':cost, 'executed':False})
                    observations.append({'name':function['name'], 'arguments':arguments, 'result':result_value})
                    continue
                tool_count+=cost
                try:
                    result_value = context.dispatch(function["name"], arguments)
                except (ValueError, TypeError) as error:
                    result_value = {"error": str(error)}
                save(directory / f"task-tool-{request_number}-{tool_count}.json",
                     {"tool_call": request, "result": result_value,"budget_cost":cost})
                observations.append({'name':function['name'], 'arguments':arguments, 'result':result_value})
            retrieving = True
        elif message.get("tool_calls") or (message.get("content") is not None and not isinstance(message["content"], str)):
            raise ValueError("expected raw LTL response or allowlisted task tool call")
        else:
            content = message.get('content')
            if retrieving and not final_slot and content is None:
                raise incomplete_response(event, 'incomplete evidence handoff; cost preserved; no automatic regeneration')
            if retrieving and not final_slot and isinstance(content, str) and content.strip()=='READY':
                if event['response_status'] != 'complete':
                    save(directory / f'incomplete-response-{request_number}.json', {
                        **event, 'stage':'evidence-handoff', 'policy':'stop-without-automatic-regeneration-v1'})
                    raise incomplete_response(event, 'incomplete evidence handoff; cost preserved; no automatic regeneration')
                # Never forward a model-written summary or premature draft as
                # evidence. Only the original task and actual read results go
                # to the separate tools-disabled authoring request.
                save(directory / f'evidence-handoff-{request_number}.json', {
                    'step':step, 'canonical_ready':message.get('content','').strip()=='READY',
                    'draft_forwarded':False, 'new_authoring_request':True})
                retrieving = False
                authoring = True
                continue
            break
    else:
        raise RuntimeError("model dialogue call budget exhausted; cost preserved")
    usage = {key: sum(c["usage"][key] for c in calls) if all(c["usage"][key] is not None for c in calls) else None
             for key in ("prompt_tokens", "completion_tokens", "total_tokens")}
    return content, {"usage": usage, **response_metadata(result), "requested_model": model,
            "reported_model": calls[-1]["reported_model"], "http_requests": len(calls),
            "skill_sha256": skill["sha256"] if skill else None,
            "skill_protocol": SKILL_PROTOCOL if skill else None, "task_tool_calls": tool_count,
            "dialogue_policy": DIALOGUE_POLICY if context is not None else None,
            "task_context_policy": context.record()["policy"] if context is not None else None}


def journal_repairs(root, destination=None, *, write=True):
    """Rebuild idempotently. Retain every failure, even without a repaired successor.

    A next attempt is a proposed change, not proof that its individual edits caused
    success. Raw sources and diagnostics never enter the model-readable skill.
    """
    root = Path(root)
    attempts = []
    for report_path in root.glob("attempt-*/harness.json"):
        directory = report_path.parent
        try:
            number = int(directory.name.removeprefix("attempt-"))
            response = json.loads((directory / "response.json").read_text()) if (directory / "response.json").exists() else None
            source = directory / "sources/model.ltl"
            if not source.exists():
                source = directory / "sources/ModelUT.scala"  # archived complete-UT runs
            if not source.exists():
                source = directory / "sources/Generated.scala"  # legacy template-based runs, archive only
            text = source.read_text() if source.exists() else (response or {}).get("ut", {}).get("source", "")
            attempts.append((number, json.loads(report_path.read_text()), text))
        except (ValueError, OSError):
            continue
    attempts.sort(key=lambda entry: entry[0])
    repairs = []
    for index, (number, report, before) in enumerate(attempts):
        if report.get("ok") or not (report.get("phase") == "typecheck" or
                (report.get("phase") in ("elaboration-check","solve") and model_repair_allowed(report))):
            continue
        following = attempts[index + 1] if index + 1 < len(attempts) else None
        row = {"attempt": number, "diagnostics": report, "source_sha256": hashlib.sha256(before.encode()).hexdigest(),
               "status": "pending", "source_available": bool(before),
               "review": "framework-only lesson requires review before skill promotion"}
        if following:
            later, outcome, after = following
            # These phases occur only after the isolated compiler succeeded.
            compiled = (outcome.get("phase") in ("wiring-check", "solve") if report.get("phase") == "elaboration-check"
                        else outcome.get("phase") in ("lower", "elaboration-check", "wiring-check", "solve")
                        or (outcome.get("phase") == "typecheck" and outcome.get("ok")))
            row.update(next_attempt=later, next_report=outcome,
                       status=("backend-accepted" if outcome.get('phase')=='solve' and outcome.get('ok') else "changed-unverified")
                           if report.get('phase')=='solve' else "compiled" if compiled else "changed-unverified",
                       diff="".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
                                                        fromfile=f"attempt-{number}", tofile=f"attempt-{later}")))
        repairs.append(row)
    result = {"scope": "private-run-artifact-not-rag", "repairs": repairs}
    if write and (repairs or destination is not None):
        save(destination or root / "compile-repairs.json", result)
    return result


def archive_tree(root, destination):
    """Backfill recognized attempt-layout runs, without rewriting old artifacts."""
    root = Path(root)
    paths = sorted(root.rglob("harness.json"))
    runs = sorted({p.parent.parent for p in paths if p.parent.name.startswith("attempt-")})
    entries = []
    for run in runs:
        record = journal_repairs(run, write=False)
        if record["repairs"]:
            entries.append({"run": str(run.resolve()), **record})
    save(destination, {"scope": "private-run-artifact-not-rag", "runs": entries,
                       "reports_scanned": len(paths),
                       "unrecognized_layout": [str(p) for p in paths if not p.parent.name.startswith("attempt-")]})
    return entries


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Archive existing compilation repairs without changing historical runs")
    parser.add_argument("run", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--recursive", action="store_true")
    args = parser.parse_args()
    if args.recursive:
        entries = archive_tree(args.run, args.out)
        print(json.dumps({"runs": len(entries), "failures": sum(len(e["repairs"]) for e in entries)}))
    else:
        journal_repairs(args.run, args.out)
