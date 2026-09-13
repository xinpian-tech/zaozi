import unittest
import tempfile
from pathlib import Path
from types import SimpleNamespace
from bidirectional_wrapper import adapt, render, verify


class BidirectionalWrapperTests(unittest.TestCase):
    def test_automatic_adaptation_retains_original_and_bfm_ownership(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            original = root/'original.v'
            original.write_text('module original(inout [3:0] pad); endmodule\n')
            task = dict(root=tmp,module_name='original',rtl_files=['original.v'],
                        bfm_overrides={'sdram_model':{'signals':{'dq':'pad'}}})
            bp = dict(module_name='original',bfm_configs=[dict(protocol='sdram_model',signals={'dq':'pad'})],
                      protocol_flows={'operation_flows':[]},
                      data_contracts={'seq_item_fields':[{'name':'pad','is_rand':True}]})
            ports = [dict(name='pad',width=4,direction='inout')]
            result, updated = adapt(task,bp,ports,root/'adapter')
            bfm = updated['bfm_configs'][0]
            self.assertEqual(bfm['signals'],dict(dq='pad_sense',drive_data='pad_drive',drive_enable='pad_enable'))
            self.assertTrue(bfm['params']['split_io'])
            self.assertEqual(updated['protocol_flows']['operation_flows'],[])
            self.assertEqual(result['bfm_overrides']['sdram_model']['signals'],bfm['signals'])
            self.assertFalse(updated['data_contracts']['seq_item_fields'][0]['is_rand'])
            design = SimpleNamespace(top=result['module_name'],parameters=(),sources=map(Path,result['rtl_files']))
            self.assertEqual(verify(result['io_adapter'],design),'original')
            self.assertEqual(task['module_name'],'original')
            self.assertEqual(bp['bfm_configs'][0]['signals'],{'dq':'pad'})

    def test_automatic_adaptation_rejects_missing_native_owner(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError,'supported native BFM owner'):
                adapt({'module_name':'original'}, {'bfm_configs':[]},
                      [dict(name='pad',width=4,direction='inout')],Path(tmp)/'adapter')
            self.assertFalse((Path(tmp)/'adapter').exists())

    def test_original_instance_and_electrical_resolution_are_retained(self):
        ports=[dict(name='pad',direction='inout',width=4),dict(name='clk',direction='input',width=1)]
        source,mapping=render('original','adapted',ports)
        self.assertEqual(source.count('original original_dut('),1)
        self.assertIn('.pad(pad)',source)
        self.assertIn('assign pad_sense = pad;',source)
        self.assertEqual(source.count(": 1'bz;"),4)
        self.assertEqual(mapping[0]['enable'],'pad_enable')
        self.assertNotIn('force',source)
        self.assertNotIn('always',source)

    def test_port_name_collisions_fail(self):
        with self.assertRaises(ValueError):
            render('original','adapted',[dict(name='pad',direction='inout',width=4),
                                       dict(name='pad_sense',direction='output',width=4)])

    def test_no_replacement_module_or_missing_inout(self):
        for name,ports in [('original',[]),('adapted',[])]:
            with self.assertRaises(ValueError): render('original',name,ports)

    def test_unimplemented_parameter_overrides_fail_closed(self):
        with self.assertRaisesRegex(ValueError,'parameter overrides'):
            verify({},SimpleNamespace(parameters=(('WIDTH',16),)))


if __name__=='__main__': unittest.main()
