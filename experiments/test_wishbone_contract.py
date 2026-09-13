"""Generic installed-component tests. No model construction or calls."""
import importlib.util
import unittest


@unittest.skipUnless(importlib.util.find_spec('haven'), 'HAVEN Python environment required')
class WishboneContractTests(unittest.TestCase):
    def blueprint(self, **address):
        return {'protocol_flows':{'bus_field_mapping':{'addr':'addr','data':'data','ack':'ack'}},
                'io_specification':{'inputs':[{'name':'addr','width':10,**address},
                                             {'name':'data','width':32}],
                                    'outputs':[{'name':'ack','width':1}]}}

    def test_absent_and_null_ranges_are_equivalent(self):
        from haven.utils.protocol_driver_renderer import _build_bus_context
        absent = _build_bus_context(self.blueprint())
        nulls = _build_bus_context(self.blueprint(low_bit=None,high_bit=None))
        self.assertEqual(absent, nulls)
        shifted = _build_bus_context(self.blueprint(low_bit=2,high_bit=11))
        self.assertEqual((shifted['addr_width'],shifted['addr_lsb']), (12,2))

    def test_invalid_range_metadata_reports_contract_error(self):
        from haven.utils.protocol_driver_renderer import _build_bus_context
        for address in ({'low_bit':-1},{'low_bit':'2'},{'high_bit':'11'},
                        {'low_bit':False},{'low_bit':2,'high_bit':10}):
            with self.subTest(address=address), self.assertRaisesRegex(ValueError,'range metadata'):
                _build_bus_context(self.blueprint(**address))

    def test_only_explicit_memory_tasks_are_exposed(self):
        from haven.utils.bfm_api import available
        api = available([{'protocol':'wishbone_slave','signals':{},'params':{}}])
        self.assertEqual(set(api['wishbone_slave']), {'backdoor_read','backdoor_write'})
        self.assertEqual(api['wishbone_slave']['backdoor_write'][0]['type'], 'logic [63:0]')

    def test_memory_init_without_bfm_fails_before_code_emission(self):
        from haven.dsl.codegen import DSLCodegen
        from haven.dsl.schema import DSLSequenceSet
        dsl = DSLSequenceSet(module_name='synthetic', sequences=[{
            'name':'probe','description':'Synthetic memory setup','init_steps':[{'type':'memory_write','name':'init',
                                        'addr':"64'h1000",'value':"32'h55"}],
            'steps':[]}])
        with self.assertRaisesRegex(ValueError,'requires an installed.*memory BFM'):
            DSLCodegen().generate(dsl)
        code = DSLCodegen().generate(dsl,bfm_configs=[{'protocol':'wishbone_slave','signals':{},'params':{}}])[0]
        self.assertIn('virtual wishbone_slave_bfm wishbone_slave_bfm_h;',code)
        self.assertIn('wishbone_slave_bfm_h.backdoor_write(',code)
        self.assertIn('uvm_config_db#(virtual wishbone_slave_bfm)::get',code)
        self.assertNotIn('$root.',code)

    def test_byte_select_parameter_arithmetic(self):
        from haven.graph.rtl_utils import extract_rtl_ports
        ports=extract_rtl_ports('module sample #(parameter DATA_WIDTH=32)(input [DATA_WIDTH/8-1:0] sel); endmodule','sample')
        self.assertEqual(ports[0]['width'],4)

    def test_read_and_poll_initialize_all_byte_lanes(self):
        from haven.dsl.codegen import DSLCodegen
        from haven.dsl.schema import DSLSequenceSet,BusFieldMapping
        dsl=DSLSequenceSet(module_name='probe',sequences=[{'name':'read_test','description':'read and poll',
            'locals':[{'name':'value','type':'logic','width':64}], 'steps':[
            {'type':'register_read','name':'read','addr':'0','store':'value'},
            {'type':'poll','name':'poll','addr':'0','store':'value','condition':'value == 0','timeout':4}]}])
        bm=BusFieldMapping(addr='a',data='d',we='w',read_data='q',sel='lanes')
        code=DSLCodegen().generate(dsl,bus_mapping=bm)[0]
        self.assertEqual(code.count("txn.lanes = '1;"),2)
        self.assertIn("end while ((value == 0) !== 1'b1 && _poll_n < 4)",code)
        self.assertIn("if ((value == 0) !== 1'b1)",code)
        self.assertNotIn('if (_poll_n >=',code)

    def test_ansi_shared_port_declarations_keep_all_names_and_widths(self):
        from haven.graph.rtl_utils import extract_rtl_ports
        ports=extract_rtl_ports('module sample(input logic clk,rst,cs_n, output wire [15:0] a,b, input logic c); endmodule','sample')
        self.assertEqual([(p['name'],p['direction'],p['width']) for p in ports],
            [('clk','input',1),('rst','input',1),('cs_n','input',1),('a','output',16),('b','output',16),('c','input',1)])

    def test_classic_wishbone_drives_optional_cycle_tags_and_extra_pins(self):
        from haven.utils.protocol_driver_renderer import ProtocolDriverRenderer
        bp=self.blueprint()
        bp.update(module_name='probe',clock={'port':'clk'},reset={'name':'rst','level':'high'})
        bp['protocol_flows']['bus_field_mapping'].update(cyc='wb_cyc_i',stb='wb_stb_i',we='we',read_data='q')
        bp['io_specification']['inputs'] += [{'name':n,'width':w} for n,w in [('wb_cyc_i',1),('wb_stb_i',1),('wb_cti_i',3),('wb_bte_i',2),('we',1),('external_control',4)]]
        code=ProtocolDriverRenderer().render_driver(bp)
        self.assertEqual(code.count("vif.wb_cti_i <= '0;"),3)
        self.assertEqual(code.count("vif.wb_bte_i <= '0;"),3)
        self.assertIn('vif.external_control <= item.external_control;',code)
        self.assertNotIn('item.wb_cti_i',code)
        bp['protocol_flows']['ack_timeout_cycles']=1000
        self.assertEqual(ProtocolDriverRenderer().render_driver(bp).count('ack_wait >= 1000'),2)
        bp['protocol_flows']['ack_timeout_cycles']=0
        with self.assertRaisesRegex(ValueError,'ACK timeout'):
            ProtocolDriverRenderer().render_driver(bp)

    def test_interface_preserves_physical_range_without_double_shift(self):
        from haven.utils.template_engine import TemplateEngine
        bp=self.blueprint(low_bit=2,high_bit=11)
        bp.update(module_name='probe',clock={'port':'clk'},reset={'name':'rst','level':'high'})
        code=TemplateEngine().render('interface',bp)
        self.assertIn('logic [11:2] addr;',code)
        from haven_shared import repair_components
        components={'seq_item':'class item; rand logic [11:0] addr; endclass',
                    'driver':'class driver; task go(); vif.addr = item.addr >> 2; endtask endclass'}
        fixed,_=repair_components(components,[{'name':'addr','width':10,'direction':'input'}],item_widths={'addr':12})
        self.assertIn('logic [11:0] addr;',fixed['seq_item'])

    def test_native_optional_ports_are_defaulted_but_required_ports_are_not(self):
        from haven.utils.environment_contract import normalize_environment
        mapping={n:n for n in ('txd','tx_en','tx_er','rxd','rx_dv','rx_er','rx_clk','tx_clk','col','crs')}
        inputs=[{'name':n,'width':4 if n=='rxd' else 1} for n in ('rxd','rx_dv','rx_er','rx_clk','tx_clk','col','crs')]
        outputs=[{'name':n,'width':4 if n=='txd' else 1} for n in ('txd','tx_en','tx_er')]
        bp={'module_name':'probe','clock':{'port':'clk'},'reset':{'name':'rst','level':'high'},
            'io_specification':{'inputs':inputs,'outputs':outputs},'topology':{'agents':[]},
            'bfm_configs':[{'protocol':'mii_phy','signals':mapping,'params':{}}]}
        normalized=normalize_environment(bp)
        self.assertEqual(normalized['bfm_configs'][0]['params']['port_defaults']['external_clock_mode'],'0')
        del mapping['txd']
        with self.assertRaisesRegex(ValueError,'unmapped ports'):normalize_environment(bp)
