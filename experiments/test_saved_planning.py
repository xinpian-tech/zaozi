import json
import unittest

try:
    from haven.utils.json_output import loads
except ImportError:
    loads=None


@unittest.skipIf(loads is None,'HAVEN dependencies required')
class SavedPlanningTests(unittest.TestCase):
    def test_saved_envelope_preserves_author_payload_and_native_metadata(self):
        from recheck_saved_planning import recover_blueprint
        payload={'module_name':'generic','topology':{'agents':[]},'extra':'keep'}
        for content in (payload,json.dumps(payload)):
            raw={'type':'json_object','content':content,'clock':{'port':'clock'}}
            self.assertEqual(recover_blueprint(raw),{**payload,'clock':{'port':'clock'}})
            self.assertEqual(raw['content'],content)
        with self.assertRaisesRegex(ValueError,'ambiguous'):
            recover_blueprint({'type':'json_object','content':payload,'unrecognized':'do not discard'})

    def test_unknown_module_never_becomes_placeholder_source_filename(self):
        from haven.utils.template_engine import TemplateEngine
        with self.assertRaisesRegex(ValueError,'explicit module_name'):
            TemplateEngine().render('filelist',{})

    def test_observer_mode_alias_is_not_a_stimulus_producer(self):
        from haven.utils.environment_contract import normalize_environment
        bp={'module_name':'generic','clock':{'port':'clk'},'reset':{'name':'rst','level':'high'},
            'io_specification':{'inputs':[{'name':'data','width':1}], 'outputs':[{'name':'out','width':1}]},
            'topology':{'agents':[{'name':'watch','mode':'monitor','output_signals':['out']}]}}
        result=normalize_environment(bp)
        self.assertEqual(result['topology']['agents'][0]['mode'],'passive')
        self.assertEqual(result['environment_contract']['agent_owned'],{})


if __name__=='__main__': unittest.main()
