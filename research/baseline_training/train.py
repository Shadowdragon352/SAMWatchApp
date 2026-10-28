"""Offline scenario-learning experiment. Outputs never mean SAD probability."""
from __future__ import annotations
import argparse
import concurrent.futures
import hashlib
import io
import json
import platform
import urllib.request
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score, roc_curve
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

BASE = 'https://physionet.org/files/wearable-device-dataset/1.0.1/'
SOURCE = 'https://physionet.org/content/wearable-device-dataset/1.0.1/'
FEATURES = ['hr_delta', 'skin_delta', 'movement_log_ratio', 'accuracy_delta', 'rt_log_ratio']
SUBJECTS = [f'S{i:02d}' for i in range(1, 19) if i != 2]  # S02 duplicated source signals
SEED = 352


def download(cache: Path, relative: str) -> Path:
    path = cache / relative
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(BASE + relative, timeout=60) as response:
            data = response.read()
        path.write_bytes(data)
    return path


def load_signal(path: Path, name: str) -> pd.DataFrame:
    lines = path.read_text().splitlines()
    start = pd.Timestamp(lines[0].split(',')[0], tz='UTC')
    hz = float(lines[1].split(',')[0])
    raw = pd.read_csv(io.StringIO('\n'.join(lines[2:])), header=None).to_numpy(float)
    index = start + pd.to_timedelta(np.arange(len(raw)) / hz, unit='s')
    if name == 'ACC':
        # Approximation for this experiment: remove a trailing 1 s gravity estimate.
        # Empatica raw units are 1/64 g; this is not the Galaxy Watch processor.
        accel = pd.DataFrame(raw * (9.80665 / 64), index=index)
        dynamic = accel - accel.rolling(max(1, round(hz)), min_periods=1).mean()
        value = np.sqrt((dynamic.to_numpy() ** 2).sum(axis=1))
    else:
        value = raw[:, 0]
    return pd.DataFrame({name: value}, index=index).resample('1min').median()


def load_subject(cache: Path, subject: str) -> pd.DataFrame:
    parts = [load_signal(cache / f'Wearable_Dataset/STRESS/{subject}/{n}.csv', n)
             for n in ['HR', 'TEMP', 'ACC']]
    frame = pd.concat(parts, axis=1).dropna()
    frame = frame[(frame.HR.between(25, 240)) & frame.TEMP.between(15, 45) & (frame.ACC >= 0)]
    if len(frame) < 12:
        raise ValueError(f'{subject}: insufficient aligned source data')
    return frame


def quality_reason(row: dict) -> str | None:
    required = ['reference_available', 'worn', 'age_seconds', 'task_complete', 'rapid_taps']
    if any(k not in row for k in required):
        return 'quality_metadata_needed'
    if any(type(row[k]) is not bool for k in required if k != 'age_seconds'):
        return 'invalid_quality_metadata'
    age = row['age_seconds']
    if not isinstance(age, (int, float)) or isinstance(age, bool) or not np.isfinite(age) or age < 0:
        return 'invalid_quality_metadata'
    if not row.get('reference_available', False):
        return 'reference_needed'
    if not row.get('worn', True):
        return 'watch_not_worn'
    if row.get('age_seconds', 0) > 120:
        return 'stale_data'
    if not row.get('task_complete', True):
        return 'task_incomplete'
    if row.get('rapid_taps', False):
        return 'task_quality_problem'
    for name, low, high in [('heart_rate_bpm', 25, 240), ('skin_temp_c', 15, 45), ('movement_rms', 0, 50)]:
        value = row.get(name)
        if not isinstance(value, (int, float)) or isinstance(value, bool) or not np.isfinite(value) or not low <= value <= high:
            return 'invalid_or_missing_sensor'
    return None


def generate(sources: dict[str, pd.DataFrame], seed: int, shifted: bool = False) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    rows = []
    for subject, source in sources.items():
        # An explicitly UNLABELLED engineering reference, not a healthy diagnosis.
        reference = source.iloc[:5].median()
        current = source.iloc[5:]
        for repetition in range(24):
            observed = current.iloc[int(rng.integers(len(current)))].copy()
            accuracy_base = rng.uniform(.72, .98)
            rt_base = rng.uniform(350, 1100)
            for scenario in ['unmodified', 'activity_context', 'rest_context', 'temperature_context',
                             'task_noise', 'scripted_reduced_activity', 'scripted_increased_activity',
                             'scripted_cognitive_change', 'scripted_mixed_change']:
                values = observed.copy()
                accuracy = np.clip(accuracy_base + rng.normal(0, .035), 0, 1)
                rt = rt_base * np.exp(rng.normal(0, .10))
                changed = scenario.startswith('scripted_')
                strength = rng.uniform(.35, .8) if shifted else rng.uniform(.6, 1.4)
                if scenario == 'activity_context':
                    values.HR += rng.uniform(5, 35)
                    values.ACC *= rng.uniform(1.5, 5)
                elif scenario == 'rest_context':
                    values.HR -= rng.uniform(0, 10)
                    values.ACC *= rng.uniform(.05, .5)
                elif scenario == 'temperature_context':
                    values.TEMP += rng.uniform(-2, 2)
                elif scenario == 'task_noise':
                    accuracy -= rng.uniform(0, .2)
                    rt *= rng.uniform(.8, 1.4)
                if changed:
                    accuracy -= rng.uniform(.08, .35) * strength
                    rt *= 1 + rng.uniform(.15, .9) * strength
                    if scenario in ['scripted_reduced_activity', 'scripted_mixed_change']:
                        values.ACC *= max(.05, 1 - .65 * strength)
                    if scenario == 'scripted_increased_activity':
                        values.ACC *= 1 + 2 * strength
                    if scenario == 'scripted_mixed_change':
                        values.HR += rng.uniform(3, 20) * strength
                        values.TEMP += rng.uniform(-.5, .8) * strength
                rows.append(dict(subject_id=subject, repetition=repetition, scenario=scenario,
                    scripted_change=int(changed), reference_kind='UNLABELLED_ENGINEERING_REFERENCE',
                    sensor_provenance='REAL_WRIST_RECORDING_WITH_SCRIPTED_TRANSFORMS',
                    cognition_provenance='FULLY_SYNTHETIC', clinical_label_available=False,
                    source_time=str(observed.name),
                    hr_delta=(values.HR-reference.HR)/5,
                    skin_delta=(values.TEMP-reference.TEMP)/.2,
                    movement_log_ratio=np.log((values.ACC+.05)/(reference.ACC+.05)),
                    accuracy_delta=np.clip(accuracy, 0, 1)-accuracy_base,
                    rt_log_ratio=np.log(rt/rt_base)))
    return pd.DataFrame(rows)


def select_threshold(y, scores, max_fpr=.05):
    fpr, tpr, thresholds = roc_curve(y, scores, drop_intermediate=False)
    allowed = np.flatnonzero(fpr <= max_fpr)
    best = min(allowed, key=lambda i: (-tpr[i], fpr[i]))
    return float(thresholds[best])


def metrics(frame, scores, threshold):
    y = frame.scripted_change.to_numpy()
    pred = scores >= threshold
    negatives = y == 0
    grouped = pd.DataFrame(dict(subject=frame.subject_id, y=y, flag=pred))
    return dict(auroc=float(roc_auc_score(y, scores)),
        average_precision=float(average_precision_score(y, scores)),
        scripted_positive_fraction=float(y.mean()),
        sensitivity=float(pred[~negatives].mean()),
        false_positive_fraction=float(pred[negatives].mean()),
        precision=float(y[pred].mean()) if pred.any() else None,
        unmodified_or_confounder_subjects_with_any_flag=float(
            grouped[grouped.y == 0].groupby('subject').flag.max().mean()),
        rows=len(y), subjects=int(frame.subject_id.nunique()))


def interpret(bundle: dict, context: dict, features: dict) -> dict:
    """Explicit quality metadata is required; no missing-reading imputation at inference."""
    reason = quality_reason(context)
    if reason:
        return dict(status='quality_notice', reason=reason, score=None, clinical_prediction=False)
    values = [features.get(c) for c in bundle['features']]
    if any(not isinstance(v, (int, float)) or isinstance(v, bool) or not np.isfinite(v) for v in values):
        return dict(status='quality_notice', reason='missing_features', score=None, clinical_prediction=False)
    score = float(bundle['model'].predict_proba(pd.DataFrame([features])[bundle['features']])[0, 1])
    return dict(status='scripted_change_flag' if score >= bundle['threshold'] else 'no_scripted_flag',
                score=score, clinical_prediction=False)


def clustered_intervals(frame, scores, threshold):
    """Exploratory subject-bootstrap intervals; only four held-out source subjects."""
    subjects = frame.subject_id.unique()
    y = frame.scripted_change.to_numpy()
    pred = scores >= threshold
    ids = frame.subject_id.to_numpy()
    rng = np.random.default_rng(SEED+100)
    values = []
    for _ in range(1000):
        idx = np.concatenate([np.flatnonzero(ids == s) for s in rng.choice(subjects, len(subjects))])
        yp, pp = y[idx], pred[idx]
        values.append([pp[yp == 1].mean(), pp[yp == 0].mean()])
    limits = np.quantile(values, [.025, .975], axis=0)
    return dict(method='1000 subject bootstrap replicates; exploratory, synthetic outcomes only',
                sensitivity_95_interval=limits[:,0].tolist(), false_positive_fraction_95_interval=limits[:,1].tolist())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, default=Path('research-cache'))
    parser.add_argument('--output', type=Path, default=Path('research-output'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    paths = ['README.txt', 'data_constraints.txt', 'LICENSE.txt'] + [
        f'Wearable_Dataset/STRESS/{s}/{n}.csv' for s in SUBJECTS for n in ['HR', 'TEMP', 'ACC']]
    print('Downloading/caching wrist recordings...', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(lambda p: download(args.cache, p), paths))
    sources = {s: load_subject(args.cache, s) for s in SUBJECTS}
    ids = np.random.default_rng(SEED).permutation(SUBJECTS).tolist()
    split = dict(train=ids[:9], validation=ids[9:13], test=ids[13:])
    assert not (set(split['train']) & set(split['test']))
    cohorts = {k: generate({s: sources[s] for s in v}, SEED+i)
               for i, (k, v) in enumerate(split.items())}
    shifted = generate({s: sources[s] for s in split['test']}, SEED+99, shifted=True)
    train, val, test = [cohorts[k] for k in ['train', 'validation', 'test']]
    experiments = {}
    fitted = {}
    print('Training watch-only and cognition-augmented candidates...', flush=True)
    for feature_set, columns in [('watch_only', FEATURES[:3]), ('synthetic_cognition_only', FEATURES[3:]), ('watch_plus_synthetic_cognition', FEATURES)]:
        for name, model in [('logistic', make_pipeline(SimpleImputer(strategy='median'), StandardScaler(),
                LogisticRegression(max_iter=1000, random_state=SEED))),
                ('boosting', HistGradientBoostingClassifier(max_iter=100, max_leaf_nodes=7,
                    min_samples_leaf=30, l2_regularization=10, early_stopping=False, random_state=SEED))]:
            key = feature_set+'_'+name
            model.fit(train[columns], train.scripted_change)
            threshold = select_threshold(val.scripted_change, model.predict_proba(val[columns])[:, 1])
            experiments[key] = dict(features=columns, threshold=threshold if np.isfinite(threshold) else None,
                disabled=not np.isfinite(threshold), validation=metrics(val, model.predict_proba(val[columns])[:,1], threshold))
            fitted[key] = (model, columns, threshold)
    # Select exclusively on validation sensitivity, then lower FPR. Test is opened after selection.
    selected = max(experiments, key=lambda k: (experiments[k]['validation']['sensitivity'],
        -experiments[k]['validation']['false_positive_fraction']))
    model, columns, threshold = fitted[selected]
    result = dict(purpose='SCRIPTED_SCENARIO_LEARNING_ONLY', clinical_validation=False,
        source=SOURCE, citation='Hongn et al. (2025), DOI:10.13026/he0v-tf17',
        license='Open Data Commons Attribution License (ODC-By)', seed=SEED,
        held_out_intervals=clustered_intervals(test, model.predict_proba(test[columns])[:,1], threshold),
        source_participants=len(sources), split=split, selected=selected, candidates=experiments,
        held_out_synthetic=metrics(test, model.predict_proba(test[columns])[:,1], threshold),
        held_out_weaker_perturbations=metrics(shifted, model.predict_proba(shifted[columns])[:,1], threshold),
        limitations=['No delirium or SAD outcomes; target is an injected change.',
            'Reference periods are unlabelled and must not be called clinically normal.',
            'Cognition and all scenario modifications are invented engineering assumptions.',
            'Within-subject windows and counterfactuals are correlated.',
            'Empatica movement processing differs from Galaxy Watch; no device equivalence demonstrated.',
            'No timing, clinical probability calibration or clinical accuracy established.',
            'Quality gate needs real hardware metadata; no automatic non-wear inference established.'],
        versions=dict(python=platform.python_version(), numpy=np.__version__, pandas=pd.__version__, sklearn=sklearn.__version__),
        downloads={p: hashlib.sha256((args.cache/p).read_bytes()).hexdigest() for p in paths})
    test_scores = model.predict_proba(test[columns])[:,1]
    result['held_out_by_scenario'] = {name: dict(rows=len(part), flag_fraction=float((test_scores[part.index] >= threshold).mean()))
        for name, part in test.groupby('scenario')}
    (args.output/'results.json').write_text(json.dumps(result, indent=2, allow_nan=False))
    for name, cohort in cohorts.items():
        cohort.to_csv(args.output/f'{name}_scenarios.csv', index=False)
    shifted.to_csv(args.output/'weaker_perturbations.csv', index=False)
    joblib.dump(dict(model=model, features=columns, threshold=threshold, research_only=True,
        target='scripted_change', clinical_prediction=False), args.output/'scenario_model.joblib')
    print(json.dumps({k: result[k] for k in ['selected', 'held_out_synthetic', 'held_out_weaker_perturbations']}, indent=2))
    print('Scores measure invented scenario recognition, NOT SAD detection. No live integration.')

if __name__ == '__main__':
    main()
