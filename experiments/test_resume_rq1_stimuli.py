import unittest
from resume_rq1_stimuli import valid_complete


class ResumeRQ1Tests(unittest.TestCase):
    def complete(self):
        return dict(status='completed',reference_check={'passed':True},
                    cells=[{'count':n} for n in range(1,9)],properties=[{'status':'complete'}])

    def test_only_verified_eight_point_sweep_reused(self):
        data=self.complete()
        self.assertTrue(valid_complete(data))
        data['cells'].pop()
        self.assertFalse(valid_complete(data))

    def test_infrastructure_exception_is_not_completed_measurement(self):
        data=self.complete()
        data['properties']=[{'status':'extension-failed'}]
        self.assertFalse(valid_complete(data))

    def test_solver_shortfall_remains_a_valid_recorded_result(self):
        data=self.complete()
        data['properties']=[{'status':'extension-shortfall'}]
        self.assertTrue(valid_complete(data))

    def test_missing_reference_agreement_cannot_be_reused(self):
        data=self.complete()
        data['reference_check']['passed']=False
        self.assertFalse(valid_complete(data))


if __name__=='__main__':
    unittest.main()
