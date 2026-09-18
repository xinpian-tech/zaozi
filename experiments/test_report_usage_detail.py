import unittest
from report_usage_detail import usage_detail


class UsageDetails(unittest.TestCase):
    def costs(self):
        def d(n):return {'reported_tokens':n,'complete':True}
        return {'usage_reported':{'prompt_tokens':100,'completion_tokens':40,'total_tokens':140},
                'token_accounting_complete':True,'usage_breakdown':{
                    'prompt_cache_hit_tokens':d(60),'cached_tokens':d(60),
                    'prompt_cache_miss_tokens':d(40),'reasoning_tokens':d(35)}}

    def test_no_double_count_or_price_inference(self):
        result=usage_detail(self.costs())
        self.assertEqual(result['cache_hit_tokens'],60)
        self.assertEqual(result['cache_hit_fraction'],.6)
        self.assertEqual(result['nonreasoning_output_tokens'],5)
        self.assertEqual(result['total_tokens'],140)
        self.assertIsNone(result['usd'])

    def test_partial_usage_does_not_claim_complete_rate_or_output_split(self):
        costs=self.costs();costs['token_accounting_complete']=False
        result=usage_detail(costs)
        self.assertIsNone(result['cache_hit_fraction'])
        self.assertIsNone(result['nonreasoning_output_tokens'])
        self.assertEqual(result['cache_hit_tokens'],60)

    def test_missing_is_not_zero(self):
        result=usage_detail({})
        self.assertIsNone(result['cache_hit_tokens'])
        self.assertIsNone(result['total_tokens'])

    def test_invalid_partition_rejected(self):
        costs=self.costs();costs['usage_reported']['prompt_tokens']=99
        with self.assertRaisesRegex(ValueError,'partition'):usage_detail(costs)


if __name__=='__main__':unittest.main()
