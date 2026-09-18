import json
import os
from pathlib import Path
import signal
import tempfile
import threading
import time
import unittest
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace
from unittest.mock import patch

import sequence_experiment as generation
from request_deadline import request_deadline, RequestDeadlineExceeded
from run_records import Records, totals, save, response_metadata
from rvprobe_skill import snapshot
from provider_failure import ProviderFailure
from sequence_framework import load_design
from task_context import TaskContext


class RequestDeadlineTests(unittest.TestCase):
    def test_absolute_deadline_restores_handler_and_timer(self):
        before = signal.getsignal(signal.SIGALRM)
        began = time.monotonic()
        with self.assertRaises(RequestDeadlineExceeded):
            with request_deadline(.05): time.sleep(.5)
        self.assertLess(time.monotonic()-began,.4)
        self.assertEqual(signal.getsignal(signal.SIGALRM),before)
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL),(0.0,0.0))
        with request_deadline(1): pass
        self.assertEqual(signal.getsignal(signal.SIGALRM),before)

    def test_existing_alarm_and_invalid_parameters_are_not_overwritten(self):
        for value in (0,-1,float('inf'),float('nan'),True):
            with self.assertRaises(ValueError):
                with request_deadline(value): pass
        signal.setitimer(signal.ITIMER_REAL,5)
        try:
            with self.assertRaisesRegex(RuntimeError,'existing'):
                with request_deadline(1): pass
            self.assertGreater(signal.getitimer(signal.ITIMER_REAL)[0],4)
        finally:
            signal.setitimer(signal.ITIMER_REAL,0)

    def test_deadline_is_not_retried_or_accounted_as_free(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3,
                             rvprobe_skill_snapshot=snapshot(),task_context=None)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(generation,'send_completion',side_effect=RequestDeadlineExceeded('deadline')) as send:
                with self.assertRaises(RequestDeadlineExceeded):
                    generation.request_model(args,'TASK',root,Records(root))
            send.assert_called_once()
            cost=totals(root)
            self.assertEqual(cost['requests'],1)
            self.assertEqual(cost['requests_without_usage'],1)
            self.assertFalse(cost['token_accounting_complete'])
            self.assertIsNone(cost['usage_reported']['total_tokens'])
            self.assertEqual(len(list(root.glob('request-*.json'))),1)
            with patch.object(generation,'send_completion') as resumed:
                with self.assertRaisesRegex(ProviderFailure,'automatic resume regeneration is disabled'):
                    generation.request_model(args,'TASK',root,Records(root))
                resumed.assert_not_called()

    def test_socket_errors_are_not_automatically_resent(self):
        for error in (TimeoutError('socket'), urllib.error.URLError(TimeoutError('socket')), ConnectionResetError()):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3)
                with patch.object(generation,'invoke',side_effect=error) as send:
                    with self.assertRaises(ProviderFailure) as raised:
                        generation.request_model(args,'TASK',root,Records(root))
                self.assertEqual(raised.exception.failure_kind,'provider_transport_unknown')
                send.assert_called_once()
                self.assertEqual(totals(root)['requests_without_usage'],1)

    def test_killed_or_unpersisted_response_is_not_resent_on_resume(self):
        for status in ('running','ok','failed'):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temp:
                root=Path(temp)
                save(root/'request-1.json',{'status':status})
                args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3)
                before=(root/'request-1.json').read_bytes()
                with patch.object(generation,'invoke') as send:
                    with self.assertRaisesRegex(ProviderFailure,'automatic resume regeneration is disabled'):
                        generation.request_model(args,'TASK',root,Records(root))
                send.assert_not_called()
                self.assertEqual((root/'request-1.json').read_bytes(),before)

    def test_http_error_after_tool_step_does_not_restart_paid_dialogue(self):
        args=SimpleNamespace(model='test',temperature=0,timeout=1,request_retries=3,
                             rvprobe_skill_snapshot=snapshot(),task_context=TaskContext(load_design(
                                 Path(__file__).parent/'tests/fixtures/tiny_design.json')))
        response={'choices':[{'finish_reason':'tool_calls','message':{'content':None,'tool_calls':[
            {'id':'one','type':'function','function':{'name':'list_rtl','arguments':'{}'}}]}}],
            'usage':{'prompt_tokens':8,'completion_tokens':2,'total_tokens':10}}
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            attempt=root/'attempt-1';attempt.mkdir()
            with patch.object(generation,'send_completion',side_effect=[response,
                    urllib.error.HTTPError('local',503,'busy',{},None)]) as send:
                with self.assertRaises(ProviderFailure):
                    generation.request_model(args,'TASK',attempt,Records(root))
            self.assertEqual(send.call_count,2)
            cost=totals(root)
            self.assertEqual(cost['usage_reported']['total_tokens'],10)
            self.assertEqual(cost['requests_without_usage'],1)

    def test_reasoning_exhaustion_is_classified_from_numeric_usage_only(self):
        response={'choices':[{'finish_reason':'length','message':{'content':'','reasoning_content':'PRIVATE'}}],
                  'usage':{'completion_tokens':65536,'completion_tokens_details':{'reasoning_tokens':65536}}}
        self.assertEqual(response_metadata(response)['response_failure_kind'],'provider_reasoning_budget_exhausted')
        response['choices'][0]['message']['content']='partial LTL'
        self.assertEqual(response_metadata(response)['response_failure_kind'],'provider_output_truncated')
        response['choices'][0]['message']['content']=''
        response['usage'].pop('completion_tokens_details')
        self.assertEqual(response_metadata(response)['response_failure_kind'],'provider_output_truncated')
        self.assertNotIn('PRIVATE',json.dumps(response_metadata(response)))

    def server(self, *, trickle):
        seen=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args): pass
            def do_POST(self):
                seen.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
                self.send_response(200)
                self.send_header('Content-Type','application/json')
                self.send_header('Connection','close')
                self.end_headers()
                try:
                    if trickle:
                        for _ in range(50):
                            self.wfile.write(b' ');self.wfile.flush();time.sleep(.01)
                    self.wfile.write(b'{"usage":{"total_tokens":7}}');self.wfile.flush()
                except (BrokenPipeError,ConnectionResetError): pass
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        thread=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':.01},daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return server,seen

    def test_trickling_http_body_cannot_extend_total_deadline(self):
        server,seen=self.server(trickle=True)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        env={'RVPROBE_LLM_API_KEY':'local-test-only','RVPROBE_LLM_BASE_URL':f'http://127.0.0.1:{server.server_port}/v1'}
        began=time.monotonic()
        with patch.dict(os.environ,env),patch.object(urllib.request,'urlopen',side_effect=opener.open):
            with self.assertRaises(RequestDeadlineExceeded):
                generation.send_completion({'model':'test','messages':[]},.15)
        self.assertLess(time.monotonic()-began,.45)
        self.assertEqual(len(seen),1)

    def test_complete_http_response_and_usage_are_unchanged(self):
        server,seen=self.server(trickle=False)
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        env={'RVPROBE_LLM_API_KEY':'local-test-only','RVPROBE_LLM_BASE_URL':f'http://127.0.0.1:{server.server_port}/v1'}
        payload={'model':'test','messages':[{'role':'user','content':'LTL'}]}
        with patch.dict(os.environ,env),patch.object(urllib.request,'urlopen',side_effect=opener.open):
            result=generation.send_completion(payload,1)
        self.assertEqual(result,{'usage':{'total_tokens':7}})
        self.assertEqual(seen,[payload])


if __name__ == '__main__': unittest.main()
