import unittest
from types import SimpleNamespace
from environment_policy import derive_policy, require_supported
from event_transport import install_event_transport
from sequence_framework import Port


class EnvironmentPolicyTests(unittest.TestCase):
    def test_inactive_device_reset_is_muxed_only_at_independent_boundary(self):
        from environment_contract import INDEPENDENT, independent_environment
        for active_low in (False, True):
            for protocol in ('wishbone_slave', 'spi_slave', 'sdram_model'):
                with self.subTest(active_low=active_low, protocol=protocol):
                    ports = [Port('clock','input',1,'clock'),Port('reset','input',1),
                             Port('response','input',1),Port('q','output',1)]
                    design = SimpleNamespace(top='probe',clock='clock',reset='reset',
                        reset_active_low=active_low,ports=ports,data_ports=ports[2:],parameters={})
                    reset = '~reset' if active_low else 'reset'
                    bp = {'bfm_configs':[{'protocol':protocol}],
                        'environment_contract':{'version':'pin-ownership-v1',
                            'bfm_owned':{'response':'bfm:'+protocol}}}
                    env = {'version':'shared-event-environment-v1',
                           'clocks':[{'port':'clock','period_ps':10000}]}
                    components = {'seq_item':'class item; endclass',
                        'interface':'interface probe_if(input clock,reset); logic response,q; endinterface',
                        'top':f'''module top; probe_if vif(clk,reset);
                            probe u_dut(.clock(clk),.reset(reset),.response(vif.response),.q(vif.q));
                            {protocol}_bfm u_{protocol}_bfm(.clk(clk),.rst({reset}),.data(vif.response));
                            initial run_test(); endmodule''',
                        'driver':'class driver; task run(); seq_item_port.get_next_item(item); endtask endclass'}
                    for boundary in (None, INDEPENDENT):
                        installed = install_event_transport(components,design,
                            {'environment':independent_environment(env) if boundary else env},
                            'driver',derive_policy(bp,boundary))
                        effective = ('~' if active_low else '')+'rvp_pin_reset'
                        expected = "vif.rvp_raw_mode ? 1'b1 : ("+effective+')' if boundary else effective
                        self.assertIn('.rst('+expected+')',installed['top'])
                        self.assertIn('.clk(rvp_pin_clock)',installed['top'])
                        self.assertIn('.rst('+reset+')',components['top'])

    def test_independent_static_inputs_are_declared_and_checked_before_driving(self):
        from environment_contract import INDEPENDENT, independent_environment, formal_assumptions
        from replay_failures import classify
        ports=[Port('clock','input',1,'clock'),Port('reset','input',1),Port('cfg','input',4)]
        design=SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                               ports=ports,data_ports=ports[2:],parameters={})
        env=independent_environment({'version':'shared-event-environment-v1',
            'clocks':[{'port':'clock','period_ps':10000}],'static':{'cfg':5}})
        components={'seq_item':'class item; endclass',
                    'interface':'interface probe_if(input clock,reset); logic [3:0] cfg; endinterface',
                    'top':'''module top; probe_if vif(clk,reset);
                        probe u_dut(.clock(clk),.reset(reset),.cfg(4'h5));
                        initial run_test(); endmodule''',
                    'driver':'class driver; task run(); seq_item_port.get_next_item(item); endtask endclass'}
        fixed=install_event_transport(components,design,{'environment':env},'driver',derive_policy({},INDEPENDENT))
        self.assertEqual(formal_assumptions(design,env),["cfg == 4'h5"])
        self.assertIn('bit [3:0] rvp_drive_cfg;',fixed['seq_item'])
        self.assertIn('bit rvp_raw = 0;',fixed['seq_item'])
        self.assertNotIn('rand ',fixed['seq_item'])
        self.assertNotIn('constraint ',fixed['seq_item'])
        self.assertIn("rvp_raw_mode ? 4'h5",fixed['top'])
        task=fixed['driver'].split('task rvp_drive_event',1)[1]
        self.assertLess(task.index('CANDIDATE_ENVIRONMENT'),task.index('vif.rvp_reset_request ='))
        self.assertIn('vif.rvp_effective_cfg !== item.rvp_drive_cfg',task)
        error=classify('UVM_FATAL driver.sv(1) @ 0: [CANDIDATE_ENVIRONMENT] requested=0 fixed=5')
        self.assertEqual(error['kind'],'candidate_environment_violation')
        self.assertTrue(error['model_repair_allowed'])
        self.assertFalse(classify('UVM_FATAL driver.sv(1) @ 0: [INPUT_WITNESS] mismatch')['model_repair_allowed'])

    def test_independent_secondary_reset_follows_its_exposed_input(self):
        from environment_contract import INDEPENDENT, independent_environment
        ports = [Port('clock','input',1,'clock'),Port('reset','input',1),
                 Port('arst_n','input',1),Port('q','output',1)]
        design = SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                                 ports=ports,data_ports=ports[2:],parameters={})
        env = {'version':'shared-event-environment-v1','clocks':[{'port':'clock','period_ps':10000}],
               'extra_resets':[{'port':'arst_n','active_low':True}]}
        components = {'seq_item':'class item; endclass',
                      'interface':'interface probe_if(input clock,reset); logic arst_n,q; endinterface',
                      'top':'''module top; probe_if vif(clk,reset);
                        probe u_dut(.clock(clk),.reset(reset),.arst_n(vif.arst_n),.q(vif.q));
                        initial run_test(); endmodule''',
                      'driver':'class driver; task run_phase; seq_item_port.get_next_item(item); endtask endclass'}
        fixed = install_event_transport(components,design,{'environment':independent_environment(env)},
                                        'driver',derive_policy({},INDEPENDENT))
        self.assertIn('rvp_pin_arst_n = vif.rvp_raw_mode ? vif.rvp_drive_arst_n',fixed['top'])
        self.assertIn('vif.rvp_effective_arst_n !== item.rvp_drive_arst_n',fixed['driver'])
        shared = install_event_transport(components,design,{'environment':env},'driver',derive_policy({}))
        self.assertIn('rvp_pin_arst_n = vif.rvp_raw_mode ? ~vif.rvp_reset_request',shared['top'])

    def test_haven_cannot_select_private_raw_mode(self):
        from dsl_validation import validate_native_interface
        dsl=SimpleNamespace(model_dump=lambda:{'sequences':[{
            'steps':[{'type':'randomize_send','constraints':['rvp_raw == 1']}]}]})
        with self.assertRaisesRegex(ValueError,'RVProbe-private'):
            validate_native_interface(dsl)

    def test_independent_boundary_removes_response_ownership_not_native_sources(self):
        from environment_contract import INDEPENDENT, independent_environment, formal_assumptions
        ports = [Port('clock','input',1,'clock'),Port('reset','input',1),
                 Port('response','input',1),Port('q','output',1)]
        design = SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                                 ports=ports,data_ports=ports[2:],parameters={})
        bp={'bfm_configs':[{'protocol':'spi_slave'}],
            'environment_contract':{'version':'pin-ownership-v1','bfm_owned':{'response':'bfm:spi_slave'}}}
        policy=derive_policy(bp,INDEPENDENT)
        self.assertEqual(policy['retained_inputs'],[])
        self.assertEqual(len(policy['bfms']),1)
        env=independent_environment({'version':'shared-event-environment-v1',
                                    'clocks':[{'port':'clock','period_ps':10000}]})
        self.assertEqual(formal_assumptions(design,env),[])
        components={'seq_item':'class item; endclass',
                    'interface':'interface probe_if(input clock,reset); logic response,q; endinterface',
                    'top':'''module top; probe_if vif(clk,reset);
                      probe u_dut(.clock(clk),.reset(reset),.response(vif.response),.q(vif.q));
                      spi_slave_bfm u_spi_slave_bfm(.clk(clk),.rst(reset),.miso(vif.response));
                      initial run_test("test"); endmodule''',
                    'driver':'class driver; task run(); forever begin seq_item_port.get_next_item(item); end endtask endclass'}
        fixed=install_event_transport(components,design,{'environment':env},'driver',policy)
        self.assertIn('rvp_raw_mode ? vif.rvp_drive_response : (vif.response)',fixed['top'])
        self.assertIn('vif.rvp_drive_response = item.rvp_drive_response;',fixed['driver'])
        self.assertIn('INPUT_WITNESS',fixed['driver'])
        self.assertIn('spi_slave_bfm u_spi_slave_bfm',fixed['top'])
        self.assertNotIn('rvp_raw_mode',components['top'])
        from replay_failures import classify
        self.assertFalse(classify('UVM_FATAL driver.sv(1) @ 0: [INPUT_WITNESS] mismatch')['model_repair_allowed'])
        with self.assertRaisesRegex(ValueError,'boundary differ'):
            install_event_transport(components,design,{'environment':env},'driver',derive_policy(bp))

    def test_independent_boundary_rejects_hidden_electrical_overrides(self):
        from environment_contract import INDEPENDENT, validate_environment
        with self.assertRaisesRegex(ValueError,'external feedback'):
            validate_environment(None,{'version':'shared-event-environment-v1',
                'boundary':INDEPENDENT,'open_drain':[{}]})

    def test_adapter_input_failures_do_not_request_model_repairs(self):
        from replay_failures import classify
        self.assertFalse(classify('sampled drive differs: row 24, port input')['model_repair_allowed'])
        self.assertFalse(classify('original LTL goal did not hold on live IO: goal')['model_repair_allowed'])
    def test_roles_do_not_depend_on_design_names(self):
        bp = {'bfm_configs':[{'protocol':'gpio'},{'protocol':'i2c_slave'}],
              'environment_contract':{'version':'pin-ownership-v1',
                  'bfm_owned':{'pins':'bfm:gpio','sda':'bfm:i2c_slave'},
                  'infrastructure_owned':{'scl':'environment:pad'},
                  'agent_owned':{'ack':'memory','req':'host'}},
              'topology':{'agents':[{'name':'memory','mode':'reactive'},{'name':'host','mode':'active'}]}}
        policy = derive_policy(bp)
        require_supported(policy)
        self.assertEqual(policy['retained_inputs'],['ack','scl','sda'])
        self.assertEqual(policy['reactive_agent_pins'],{'ack':'memory'})
        self.assertNotIn('pins',policy['retained_inputs'])

    def test_unknown_and_mixed_models_do_not_become_unrestricted_inputs(self):
        for protocol in ['new_unknown','mii_phy']:
            with self.assertRaises(ValueError):
                require_supported(derive_policy({'bfm_configs':[{'protocol':protocol}],
                    'environment_contract':{'version':'pin-ownership-v1'}}))

    def test_response_pin_retained_and_checked_and_device_clock_rebound(self):
        ports = [Port('clock','input',1,'clock'),Port('reset','input',1),
                 Port('request','input',1),Port('response','input',1),Port('q','output',1)]
        design = SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                                 ports=ports,data_ports=ports[2:],parameters={})
        components = {'seq_item':'class item; endclass',
                      'interface':'interface probe_if(input clock,reset); logic request,response,q; endinterface',
                      'top':'''module top;
                        probe_if vif(clk, reset);
                        probe u_dut(.clock(clk),.reset(reset),.request(vif.request),.response(vif.response),.q(vif.q));
                        spi_slave_bfm u_spi_slave_bfm(.clk(clk),.rst(reset),.miso(vif.response));
                        initial run_test("test"); endmodule''',
                      'driver':'class driver; task run(); forever begin seq_item_port.get_next_item(item); end endtask endclass'}
        policy = derive_policy({'bfm_configs':[{'protocol':'spi_slave'}],
            'environment_contract':{'version':'pin-ownership-v1','bfm_owned':{'response':'bfm:spi_slave'}}})
        env = {'version':'shared-event-environment-v1','clocks':[{'port':'clock','period_ps':10000}]}
        fixed = install_event_transport(components,design,{'environment':env},'driver',policy)
        self.assertIn('rvp_raw_mode ? vif.response : (vif.response)',fixed['top'])
        self.assertIn('.clk(rvp_pin_clock),.rst(rvp_pin_reset)',fixed['top'])
        self.assertIn('ENVIRONMENT_WITNESS',fixed['driver'])
        self.assertNotIn('vif.rvp_drive_response =',fixed['driver'])
        self.assertIn('vif.rvp_drive_request =',fixed['driver'])
        self.assertIn('!item.rvp_reset',fixed['driver'])
        self.assertNotIn('rvp_pin',components['top'])

    def test_reactive_driver_gets_effective_interface_without_raw_response_writes(self):
        ports = [Port('clock','input',1,'clock'),Port('reset','input',1),
                 Port('ack','input',1),Port('request','output',1)]
        design = SimpleNamespace(top='probe',clock='clock',reset='reset',reset_active_low=False,
                                 ports=ports,data_ports=ports[2:],parameters={})
        components = {'seq_item':'class item; endclass',
                      'interface':'interface probe_if(input clock,reset); logic ack,request; endinterface',
                      'top':'''module top; probe_if vif(clk,reset);
                        probe u_dut(.clock(clk),.reset(reset),.ack(vif.ack),.request(vif.request));
                        initial run_test("test"); endmodule''',
                      'driver':'class driver; task run(); forever begin seq_item_port.get_next_item(item); end endtask endclass'}
        policy = derive_policy({'topology':{'agents':[{'name':'memory','mode':'reactive'}]},
            'environment_contract':{'version':'pin-ownership-v1','agent_owned':{'ack':'memory'}}})
        env = {'version':'shared-event-environment-v1','clocks':[{'port':'clock','period_ps':10000}]}
        fixed = install_event_transport(components,design,{'environment':env},'driver',policy)
        self.assertIn('probe_if rvp_devices(rvp_pin_clock, rvp_pin_reset)',fixed['top'])
        self.assertIn('assign vif.ack = rvp_devices.ack;',fixed['top'])
        self.assertIn('assign rvp_devices.request = vif.request;',fixed['top'])
        self.assertIn('"*m_memory.m_driver", "vif", rvp_devices',fixed['top'])
        self.assertNotIn('vif.rvp_drive_ack =',fixed['driver'])
