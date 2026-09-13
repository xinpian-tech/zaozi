"""Template transformation regressions; licensed baseline exercises actual SV."""
import unittest
from haven_shared import repair_axi_response_sampling


class AxiResponseRepairTest(unittest.TestCase):
    def test_non_axi_driver_unchanged(self):
        original = {"driver": "task drive_item(); endtask"}
        self.assertEqual(repair_axi_response_sampling(original), (original, []))

    def test_unrecognized_axi_driver_rejected(self):
        with self.assertRaises(ValueError):
            repair_axi_response_sampling({"driver": "  task axi_read();\n  endtask"})

    def test_response_listener_joins_address_workers(self):
        original = {"driver": '''  task axi_write();
    int timeout_cnt;
    fork
      begin : aw_handshake
        @(posedge clk);
      end
      begin : w_handshake
        @(posedge clk);
      end
    join
    // Phase 2: Wait for B response
    timeout_cnt = 0;
    @(posedge clk);
    `uvm_error("B", "timeout")
  endtask
  task axi_read();
    int timeout_cnt;
    // Wait for AR handshake
    @(posedge clk);
    timeout_cnt++;
    // Phase 2: Wait for R response
    timeout_cnt = 0;
    @(posedge clk);
    data = vif.rdata;
  endtask'''}
        fixed, changes = repair_axi_response_sampling(original)
        self.assertTrue(changes)
        self.assertNotIn("response_handshake", original["driver"])
        self.assertEqual(fixed["driver"].count("int response_count;"), 2)
        self.assertNotIn("`uvm_error", fixed["driver"])
        for task in fixed["driver"].split("  endtask")[:2]:
            self.assertLess(task.index("fork"), task.index("response_handshake"))
            self.assertLess(task.index("response_handshake"), task.index("join"))
        self.assertIn("data = vif.rdata;", fixed["driver"])
