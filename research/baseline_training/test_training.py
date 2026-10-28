import unittest
import tempfile
from pathlib import Path
import numpy as np
import pandas as pd
from train import FEATURES, generate, quality_reason, select_threshold, interpret, load_signal


class TrainingChecks(unittest.TestCase):
    def valid(self):
        return dict(reference_available=True, worn=True, age_seconds=10,
                    task_complete=True, rapid_taps=False, heart_rate_bpm=75,
                    skin_temp_c=33, movement_rms=.4)

    def test_correct_use(self):
        self.assertIsNone(quality_reason(self.valid()))

    def test_improper_use_and_missing_inputs(self):
        cases = [('reference_available', False, 'reference_needed'),
                 ('worn', False, 'watch_not_worn'),
                 ('age_seconds', 121, 'stale_data'),
                 ('task_complete', False, 'task_incomplete'),
                 ('rapid_taps', True, 'task_quality_problem'),
                 ('heart_rate_bpm', None, 'invalid_or_missing_sensor'),
                 ('heart_rate_bpm', np.nan, 'invalid_or_missing_sensor'),
                 ('heart_rate_bpm', np.inf, 'invalid_or_missing_sensor'),
                 ('heart_rate_bpm', 900, 'invalid_or_missing_sensor'),
                 ('skin_temp_c', -1, 'invalid_or_missing_sensor'),
                 ('movement_rms', -5, 'invalid_or_missing_sensor')]
        for field, value, expected in cases:
            with self.subTest(field=field, value=value):
                row = self.valid()
                row[field] = value
                self.assertEqual(quality_reason(row), expected)

    def test_no_flags_allowed_when_no_discrimination(self):
        threshold = select_threshold([0, 0, 1, 1], [.5, .5, .5, .5])
        self.assertTrue(np.isinf(threshold))

    def test_threshold_obeys_validation_fpr_constraint(self):
        y = np.array([0, 0, 0, 0, 1, 1])
        scores = np.array([.1, .1, .2, .3, .25, .9])
        threshold = select_threshold(y, scores)
        self.assertLessEqual((scores[y == 0] >= threshold).mean(), .05)

    def test_quality_gate_never_calls_model_for_nonwear(self):
        class MustNotRun:
            def predict_proba(self, frame):
                raise AssertionError('model must not run')
        context = self.valid()
        context['worn'] = False
        result = interpret(dict(model=MustNotRun(), features=FEATURES, threshold=.8), context, {})
        self.assertEqual(result['status'], 'quality_notice')
        self.assertIsNone(result['score'])

    def test_missing_features_do_not_become_normal_predictions(self):
        result = interpret(dict(features=FEATURES), self.valid(), {})
        self.assertEqual(result['reason'], 'missing_features')

    def test_accelerometer_repeated_header_timestamp(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'ACC.csv'
            path.write_text('2013-02-20 17:55:19,2013-02-20 17:55:19,2013-02-20 17:55:19\n1,1,1\n1,1,1\n2,2,2\n')
            signal = load_signal(path, 'ACC')
            self.assertEqual(str(signal.index[0]), '2013-02-20 17:55:00+00:00')

    def test_seeded_generation_and_provenance(self):
        source = pd.DataFrame(dict(HR=np.linspace(65, 80, 20),
            TEMP=np.linspace(32, 33, 20), ACC=np.linspace(.1, .4, 20)),
            index=pd.date_range('2020-01-01', periods=20, freq='min', tz='UTC'))
        a = generate({'S01': source}, 10)
        pd.testing.assert_frame_equal(a, generate({'S01': source}, 10))
        self.assertFalse(a.clinical_label_available.any())
        self.assertEqual(a.subject_id.unique().tolist(), ['S01'])
        self.assertTrue(np.isfinite(a[FEATURES]).all().all())
        self.assertTrue(a[a.scenario == 'unmodified'].scripted_change.eq(0).all())
        self.assertTrue(a[a.scenario.str.startswith('scripted_')].scripted_change.eq(1).all())
        self.assertNotIn('scenario', FEATURES)
        self.assertNotIn('scripted_change', FEATURES)

if __name__ == '__main__':
    unittest.main()
