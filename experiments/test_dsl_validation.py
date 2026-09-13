import unittest
from types import SimpleNamespace
from dsl_validation import validate_storage, validate_native_interface


class NativeInterfaceTests(unittest.TestCase):
    def test_nested_raw_access_is_rejected(self):
        for step in ({'constraints':['rvp_drive_awvalid == 1']},
                     {'body':[{'value':'item.rvp_raw'}]}):
            with self.subTest(step=step), self.assertRaisesRegex(ValueError,'RVProbe-private'):
                validate_native_interface(SimpleNamespace(model_dump=lambda:{'sequences':[{'steps':[step]}]}))

    def test_native_constraints_and_explanatory_text_remain_valid(self):
        validate_native_interface(SimpleNamespace(model_dump=lambda:{'sequences':[{
            'description':'rvp_raw is not used here',
            'steps':[{'constraints':['addr == 4', 'wdata == 0']}]}]}))


class StorageTests(unittest.TestCase):
    def dsl(self,locals,store):
        seq={'name':'example','locals':locals,'params':[], 'init_steps':[],
             'steps':[{'type':'register_read','name':'read_status','store':store}]}
        return SimpleNamespace(model_dump=lambda:{'sequences':[seq]})

    def test_missing_read_destination_fails_before_codegen(self):
        with self.assertRaisesRegex(ValueError,'read_status.*not a declared'):
            validate_storage(self.dsl([],'status'))

    def test_declared_local_and_slice_are_preserved(self):
        for store in ('status','status[7:0]'):
            validate_storage(self.dsl([{'name':'status'}],store))

    def test_duplicates_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicate'):
            validate_storage(self.dsl([{'name':'status'},{'name':'status'}],'status'))
