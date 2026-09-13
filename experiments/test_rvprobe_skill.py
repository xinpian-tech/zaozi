import json
import os
import re
import tempfile
import unittest
import urllib.error
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import sequence_experiment as generation
from run_records import Records, save, totals, framework_hashes
from rvprobe_skill import snapshot, load_snapshot, journal_repairs, SKILL_PROTOCOL
from sequence_framework import load_design
from task_context import TaskContext, TOOLS


class SkillTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.args = SimpleNamespace(model="test-model", temperature=0, timeout=5,
                                    request_retries=1, rvprobe_skill_snapshot=snapshot())

    def response(self, content="answer", finish="stop"):
        return {"choices": [{"message": {"role":"assistant","content":content}, "finish_reason":finish}],
                "model":"test-model","usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

    def run_request(self):
        return generation.request_model(self.args, "test task", self.root, Records(self.root))

    def test_real_http_request_contains_skill_and_task_without_bootstrap(self):
        payloads=[]
        result=self.response()
        class Response:
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def read(self): return json.dumps(result).encode()
        def open_request(request,timeout):
            payloads.append(json.loads(request.data));return Response()
        with patch.dict("os.environ",{"RVPROBE_LLM_API_KEY":"secret-test","RVPROBE_LLM_BASE_URL":"https://invalid.test"}), patch("urllib.request.urlopen",side_effect=open_request):
            text,info=self.run_request()
        self.assertEqual(text,"answer");self.assertEqual(len(payloads),1)
        messages=payloads[0]["messages"]
        self.assertEqual([m["role"] for m in messages],["user","user"])
        self.assertEqual(messages[0]["content"].split("\n",1)[1],snapshot()["content"])
        self.assertNotIn(snapshot()["sha256"], messages[0]["content"])
        self.assertEqual(messages[-1]["content"],"test task")
        self.assertNotIn("tools",payloads[0])
        self.assertEqual(info["skill_protocol"],SKILL_PROTOCOL)
        self.assertEqual(totals(self.root)["requests"],1)
        self.assertEqual(totals(self.root)["usage_reported"]["total_tokens"],12)
        self.assertNotIn("secret-test","".join(p.read_text() for p in self.root.iterdir()))

    def test_two_rounds_reuse_same_frozen_skill_without_skill_calls(self):
        frozen=self.args.rvprobe_skill_snapshot
        path=self.root/"frozen-skill.json";save(path,frozen)
        for number in (1,2):
            directory=self.root/f"round-{number}";directory.mkdir()
            self.args.rvprobe_skill_snapshot=load_snapshot(path)
            self.args.task_context=TaskContext(load_design(Path(__file__).parent/"tests/fixtures/tiny_design.json"))
            with patch.object(generation,"send_completion",return_value=self.response()) as send:
                generation.request_model(self.args,f"coverage round {number}",directory,Records(directory))
            payload=send.call_args.args[0]
            self.assertEqual(send.call_count,1);self.assertEqual(payload["tools"],TOOLS)
            self.assertNotIn("read_skill",[t["function"]["name"] for t in payload["tools"]])
            self.assertEqual(payload["messages"][0]["content"].split("\n",1)[1],frozen["content"])
        changed=dict(frozen,content="tampered");save(path,changed)
        with self.assertRaisesRegex(ValueError,"invalid frozen skill"):load_snapshot(path)

    def test_haven_path_has_no_skill(self):
        del self.args.rvprobe_skill_snapshot
        with patch.object(generation,"invoke",return_value=("answer",{"usage":{"total_tokens":7}})) as invoke:
            self.assertEqual(self.run_request()[0],"answer")
            invoke.assert_called_once_with("test task","test-model",0,5)
        self.assertEqual(totals(self.root)["requests"],1)

    def test_incomplete_output_stops_without_retry_and_preserves_cost(self):
        for skill in (True,False):
            for content,finish in (("", "length"),("partial","length"),(None,"stop"),("filtered","content_filter")):
                with self.subTest(skill=skill,finish=finish),tempfile.TemporaryDirectory() as path:
                    args=SimpleNamespace(model="test",temperature=0,timeout=1,request_retries=3)
                    if skill:args.rvprobe_skill_snapshot=snapshot()
                    root=Path(path)
                    result=self.response(content,finish)
                    result["choices"][0]["message"]["reasoning_content"]="PRIVATE_REASONING"
                    with patch.object(generation,"send_completion",return_value=result) as send:
                        with self.assertRaisesRegex(RuntimeError,"no automatic regeneration"):
                            generation.request_model(args,"task",root,Records(root))
                        with self.assertRaisesRegex(RuntimeError,"automatic resume regeneration is disabled"):
                            generation.request_model(args,"task",root,Records(root))
                    self.assertEqual(send.call_count,1)
                    self.assertEqual(totals(root)["usage_reported"]["total_tokens"],12)
                    self.assertNotIn("PRIVATE_REASONING","".join(p.read_text() for p in root.iterdir()))

    def test_tool_followup_failure_preserves_usage(self):
        self.args.task_context=TaskContext(load_design(Path(__file__).parent/"tests/fixtures/tiny_design.json"))
        response=self.response(None,"tool_calls")
        response["choices"][0]["message"]["tool_calls"]=[{"id":"call-1","type":"function","function":{"name":"list_rtl","arguments":"{}"}}]
        with patch.object(generation,"send_completion",side_effect=[response,urllib.error.URLError("offline")]):
            with self.assertRaises(RuntimeError):self.run_request()
        costs=totals(self.root)
        self.assertEqual(costs["requests"],2);self.assertEqual(costs["usage_reported"]["total_tokens"],12)
        self.assertEqual(costs["requests_without_usage"],1)

    def test_accepted_history_excludes_failed_candidates(self):
        from coverage_flow import accepted_rvprobe_history
        candidates=[{"sequences":["accepted"],"ltl":{"source":"own accepted UT"}},
                    {"sequences":["failed"],"ltl":{"source":"own failed UT"}}]
        result=accepted_rvprobe_history(candidates,["baseline","accepted"])
        self.assertEqual(result,{"ltls":[{"source":"own accepted UT"}]})
        result["ltls"][0]["source"]="changed"
        self.assertEqual(candidates[0]["ltl"]["source"],"own accepted UT")

    def test_skill_changes_invalidate_fingerprint(self):
        (self.root / "experiments").mkdir()
        (self.root / "experiments/package.mill").write_text("")
        path = self.root / "rvprobe-skill.md"
        path.write_text("one")
        before = framework_hashes(self.root)
        path.write_text("two")
        self.assertNotEqual(before, framework_hashes(self.root))

    def test_repair_journal_pending_diff_and_idempotence(self):
        def attempt(number, source, phase, ok):
            directory = self.root / f"attempt-{number}"
            (directory / "sources").mkdir(parents=True)
            (directory / "sources/ModelUT.scala").write_text(source)
            save(directory / "harness.json", {"phase": phase, "ok": ok, "errors": ["private DUT detail"]})
        attempt(1, "private_bad\n", "typecheck", False)
        self.assertEqual(journal_repairs(self.root)["repairs"][0]["status"], "pending")
        attempt(2, "private_fixed\n", "solve", True)
        record = journal_repairs(self.root)
        self.assertEqual(record["repairs"][0]["status"], "compiled")
        self.assertIn("+private_fixed", record["repairs"][0]["diff"])
        self.assertEqual(record, journal_repairs(self.root))
        self.assertNotIn("private_fixed", snapshot()["content"])


@unittest.skipUnless(os.environ.get("RVPROBE_TEST_SKILL_COMPILE") == "1", "opt-in real Nix/compiler test")
class SkillHelperCompileTests(unittest.TestCase):
    def test_actual_skill_helpers_accept_io_nodes_and_constants(self):
        from sequence_framework import ROOT, write_sources
        from test_support import goal_response
        snippets = re.findall(r"```scala\n(.*?)\n```", snapshot()["content"], re.S)
        helpers = [snippet for snippet in snippets if "def same(" in snippet]
        self.assertEqual(len(helpers), 1)
        # Compile the actual skill text, not a second hand-maintained example.
        body = "{\n" + helpers[0] + "\ngated(same(payload, BigInt(0).B(8)), valid)\n}"
        design = load_design(Path(__file__).parent / "tests/fixtures/tiny_design.json")
        with tempfile.TemporaryDirectory(prefix="rvprobe-skill-types-") as temporary:
            root = Path(temporary)
            for name, expression, expected in (
                ("valid", body, True),
                ("datatype_not_reference", body.replace("Referable[Bits]", "Bits").replace("Referable[Bool]", "Bool"), False),
            ):
                response = goal_response("helper_types", expression, design)
                sources = root / name / "sources"
                write_sources(sources, design, response)
                report, log = generation.harness(sources, root / name / "compile", ROOT / "experiments/eda-shell", compile_only=True)
                self.assertEqual(report.get("ok"), expected, str(report) + log[-2000:])
                self.assertEqual(report["phase"], "typecheck")
                self.assertEqual(totals(root / name)["requests"], 0)


if __name__ == "__main__":
    unittest.main()
