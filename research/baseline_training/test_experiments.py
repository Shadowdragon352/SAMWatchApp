import unittest
import numpy as np
import pandas as pd
from experiments import bank, scale_changes, quality_checks
from train import FEATURES

class ExperimentChecks(unittest.TestCase):
    def source(self):
        return {'S01':pd.DataFrame(dict(HR=np.linspace(65,80,20),TEMP=np.linspace(32,33,20),ACC=np.linspace(.1,.4,20)),index=pd.date_range('2020-01-01',periods=20,freq='min',tz='UTC'))}

    def test_learning_banks_are_nested_and_keep_source_identity(self):
        small=bank(self.source(),101,1)
        large=bank(self.source(),101,3)
        pd.testing.assert_frame_equal(small,large.iloc[:len(small)].reset_index(drop=True))
        self.assertEqual(set(large.subject_id),{'S01'})
        self.assertFalse(large.duplicated(['subject_id','repetition','scenario']).any())

    def test_strength_changes_are_paired_and_never_modify_negatives(self):
        original=bank(self.source(),101,1)
        zero=scale_changes(original,0)
        anchors=original[original.scenario.eq('unmodified')].set_index(['subject_id','repetition'])
        for _,row in zero[zero.scripted_change.eq(1)].iterrows():
            np.testing.assert_allclose(row[FEATURES].to_numpy(float),anchors.loc[(row.subject_id,row.repetition),FEATURES].to_numpy(float))
        pd.testing.assert_frame_equal(original[original.scripted_change.eq(0)],zero[zero.scripted_change.eq(0)])
        pd.testing.assert_frame_equal(original,scale_changes(original,1))
        with self.assertRaises(ValueError): scale_changes(original,-1)

    def test_quality_cases_are_explicit_and_all_pass(self):
        cases=quality_checks()
        self.assertEqual(len(cases),8)
        self.assertTrue(all(c['passed'] for c in cases))
        self.assertEqual(next(c for c in cases if c['case']=='Watch removed')['actual'],'watch_not_worn')

if __name__ == '__main__': unittest.main()
