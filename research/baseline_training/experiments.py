"""Measured, exploratory synthetic scenario experiments; never clinical validation."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.impute import SimpleImputer
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from train import FEATURES, SUBJECTS, SEED, load_subject, generate, select_threshold, metrics, quality_reason

RUN_SEEDS = [101, 202, 303]
BLOCK_COUNTS = [1, 3, 6]
STRENGTHS = [0, .25, .5, .75, 1, 1.25]
BLUE, TEAL, ORANGE = '#315C95', '#258477', '#C16B36'


def bank(sources, seed, blocks):
    frames = []
    for block in range(blocks):
        frame = generate(sources, seed + block * 10007)
        frame['repetition'] += block * 24
        frames.append(frame)
    return pd.concat(frames, ignore_index=True)


def scale_changes(frame, strength):
    """Paired interpolation in feature space, not a physiological disease model."""
    if strength < 0:
        raise ValueError('Strength must be nonnegative')
    out = frame.copy()
    anchors = frame[frame.scenario == 'unmodified'].set_index(['subject_id', 'repetition'])
    if anchors.index.has_duplicates:
        raise ValueError('Reference pair keys must be unique')
    positive = frame.scripted_change.eq(1)
    for index in frame.index[positive]:
        row = frame.loc[index]
        anchor = anchors.loc[(row.subject_id, row.repetition), FEATURES].to_numpy(float)
        original = row[FEATURES].to_numpy(float)
        out.loc[index, FEATURES] = anchor + strength * (original-anchor)
    return out


def candidate(name, seed):
    if name == 'logistic':
        return make_pipeline(SimpleImputer(strategy='median'), StandardScaler(), LogisticRegression(max_iter=1000, random_state=seed))
    return HistGradientBoostingClassifier(learning_rate=.1, max_iter=100, max_leaf_nodes=7,
        min_samples_leaf=30, l2_regularization=10, early_stopping=False, random_state=seed)


def fit_eval(tr, va, evaluation, columns, name, seed):
    model = candidate(name, seed)
    model.fit(tr[columns], tr.scripted_change)
    threshold = select_threshold(va.scripted_change, model.predict_proba(va[columns])[:,1])
    scores = model.predict_proba(evaluation[columns])[:,1]
    return model, threshold, scores, metrics(evaluation, scores, threshold)


def save_plot(fig, output, name):
    fig.text(.02, .015, 'EXPLORATORY SYNTHETIC SCENARIOS • not SAD detection accuracy', fontsize=10, color='#555555')
    fig.tight_layout(rect=[0, .06, 1, .97])
    fig.savefig(output/f'{name}.png', dpi=220, facecolor='white')
    fig.savefig(output/f'{name}.svg', facecolor='white')
    plt.close(fig)


def quality_checks():
    good = dict(reference_available=True, worn=True, age_seconds=10, task_complete=True,
                rapid_taps=False, heart_rate_bpm=75., skin_temp_c=33., movement_rms=.3)
    cases = [('Correct use', {}, None), ('Watch removed', {'worn':False}, 'watch_not_worn'),
        ('Stale readings', {'age_seconds':180}, 'stale_data'),
        ('Task abandoned', {'task_complete':False}, 'task_incomplete'),
        ('Rapid tapping', {'rapid_taps':True}, 'task_quality_problem'),
        ('Missing reading', {'heart_rate_bpm':None}, 'invalid_or_missing_sensor'),
        ('Impossible reading', {'heart_rate_bpm':900}, 'invalid_or_missing_sensor'),
        ('Missing baseline', {'reference_available':False}, 'reference_needed')]
    rows=[]
    for name, update, expected in cases:
        actual=quality_reason({**good, **update})
        assert actual == expected, name
        rows.append(dict(case=name, expected=expected or 'allow', actual=actual or 'allow', passed=True))
    return rows


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--cache', type=Path, default=Path('research-cache'))
    p.add_argument('--output', type=Path, default=Path('experiment-output'))
    args=p.parse_args(); out=args.output; out.mkdir(parents=True, exist_ok=True)
    sources={s:load_subject(args.cache,s) for s in SUBJECTS}
    ids=np.random.default_rng(SEED).permutation(SUBJECTS).tolist()
    split=dict(train=ids[:9], validation=ids[9:13], exploratory_evaluation=ids[13:])
    assert len(set(sum(split.values(), []))) == 17
    train_sources={s:sources[s] for s in split['train']}
    # This evaluation cohort was inspected in the earlier experiment. It is NOT a new final test.
    va=bank({s:sources[s] for s in split['validation']}, 700001, 2)
    evaluation=bank({s:sources[s] for s in split['exploratory_evaluation']}, 800001, 2)
    print('Frozen design: 9 training / 4 validation / 4 exploratory evaluation source participants', flush=True)
    learning=[]; fitted={}
    for seed in RUN_SEEDS:
        full=bank(train_sources,seed,max(BLOCK_COUNTS))
        for blocks in BLOCK_COUNTS:
            tr=full[full.repetition < blocks*24]
            print(f'Training seed {seed}: {len(tr)} synthetic rows', flush=True)
            model, threshold, scores, result=fit_eval(tr,va,evaluation,FEATURES,'boosting',seed)
            learning.append(dict(seed=seed,train_rows=len(tr),training_source_participants=9,**result))
            if blocks == max(BLOCK_COUNTS): fitted[seed]=(model,threshold,scores)
    learning=pd.DataFrame(learning); learning.to_csv(out/'learning_curve.csv',index=False)
    grouped=learning.groupby('train_rows')
    fig,ax=plt.subplots(figsize=(9,5.4))
    for metric,label,color in [('sensitivity','Scripted-change detection',BLUE),('false_positive_fraction','Negative-scenario flags',ORANGE)]:
        stats=grouped[metric].agg(['mean','min','max'])
        ax.plot(stats.index,stats['mean']*100,'o-',label=label,color=color,lw=2)
        ax.fill_between(stats.index,stats['min']*100,stats['max']*100,color=color,alpha=.15)
    ax.set(title='Does adding synthetic training data help?',xlabel='Synthetic training rows (same 9 real source participants)',ylabel='Evaluation rows (%)',ylim=(0,100))
    ax.legend(loc='center right'); ax.grid(axis='y',alpha=.2)
    ax.text(.02,.20,'Lines: mean of 3 generator seeds; shading: min–max, not a confidence interval.\nEvaluation data and validation threshold rule stay fixed.',transform=ax.transAxes,fontsize=9)
    save_plot(fig,out,'01_learning_curve')

    strengths=[]
    for strength in STRENGTHS:
        scaled=scale_changes(evaluation,strength)
        for seed,(model,threshold,_) in fitted.items():
            strengths.append(dict(seed=seed,strength=strength,**metrics(scaled,model.predict_proba(scaled[FEATURES])[:,1],threshold)))
    strengths=pd.DataFrame(strengths); strengths.to_csv(out/'change_strength.csv',index=False)
    fig,ax=plt.subplots(figsize=(9,5.4))
    stats=strengths.groupby('strength').sensitivity.agg(['mean','min','max'])
    ax.plot(stats.index,stats['mean']*100,'o-',color=BLUE,lw=2)
    ax.fill_between(stats.index,stats['min']*100,stats['max']*100,color=BLUE,alpha=.15)
    ax.set(title='Subtle scripted changes are harder to detect',xlabel='Injected feature-change multiplier (1 = standard generator)',ylabel='Flags among scripted-positive rows (%)',ylim=(0,100))
    ax.grid(alpha=.2)
    ax.text(.02,.06,'Multiplier 0: injected change removed; positive rows match their reference pair.\nPaired feature-space interpolation; 3 seeds, min–max band. No clinical severity scale.',transform=ax.transAxes,fontsize=9)
    save_plot(fig,out,'02_change_strength')

    comparisons=[]
    # Predeclared comparisons, not a search for a better-looking evaluation winner.
    tr=bank(train_sources,RUN_SEEDS[0],max(BLOCK_COUNTS))
    for group,columns in [('Watch only',FEATURES[:3]),('Synthetic cognition only',FEATURES[3:]),('Combined',FEATURES)]:
        for algorithm in ['logistic','boosting']:
            _,_,_,result=fit_eval(tr,va,evaluation,columns,algorithm,RUN_SEEDS[0])
            comparisons.append(dict(inputs=group,algorithm=algorithm,**result))
    comparisons=pd.DataFrame(comparisons);comparisons.to_csv(out/'model_comparison.csv',index=False)
    fig,ax=plt.subplots(figsize=(10,5.7))
    y=np.arange(len(comparisons)); height=.35
    ax.barh(y-height/2,comparisons.sensitivity*100,height,color=BLUE,label='Scripted-change detection')
    ax.barh(y+height/2,comparisons.false_positive_fraction*100,height,color=ORANGE,label='Negative-scenario flags')
    ax.set_yticks(y,[f'{r.inputs}\n{r.algorithm}' for r in comparisons.itertuples()])
    ax.invert_yaxis();ax.set(xlim=(0,100),xlabel='Exploratory evaluation rows (%)',title='Where does model performance come from?')
    ax.legend(loc='lower right');ax.grid(axis='x',alpha=.2)
    save_plot(fig,out,'03_input_comparison')

    errors=[]
    negative=evaluation.scripted_change.eq(0)
    for seed,(_,threshold,scores) in fitted.items():
        for scenario,part in evaluation[negative].groupby('scenario'):
            flags=scores[part.index]>=threshold
            subject_flags=pd.DataFrame(dict(subject=part.subject_id,flag=flags)).groupby('subject').flag.max()
            errors.append(dict(seed=seed,scenario=scenario,rows=len(part),flag_fraction=float(flags.mean()),source_participants_with_any_flag=float(subject_flags.mean())))
    errors=pd.DataFrame(errors);errors.to_csv(out/'false_flags.csv',index=False)
    fig,ax=plt.subplots(figsize=(9,5.4))
    stats=errors.groupby('scenario').flag_fraction.agg(['mean','min','max']).sort_values('mean')
    ax.barh(np.arange(len(stats)),stats['mean']*100,color=ORANGE)
    ax.errorbar(stats['mean']*100,np.arange(len(stats)),xerr=np.array([stats['mean']-stats['min'],stats['max']-stats['mean']])*100,fmt='none',ecolor='#333333',capsize=4)
    ax.set_yticks(np.arange(len(stats)),[s.replace('_',' ').capitalize() for s in stats.index])
    ax.set(title='Which ordinary-use scenarios cause false flags?',xlabel='Negative-scenario rows flagged (%)',xlim=(0,100))
    ax.grid(axis='x',alpha=.2)
    ax.text(.02,.05,'Bars: mean of 3 seeds; whiskers: min–max. Repeated scenarios are correlated.\nThese are synthetic case fractions, not false alarms per hour or per real patient.',transform=ax.transAxes,fontsize=9)
    save_plot(fig,out,'04_false_flags')
    checks=quality_checks(); pd.DataFrame(checks).to_csv(out/'quality_checks.csv',index=False)
    source_hashes={str(path.relative_to(args.cache)):hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((args.cache/'Wearable_Dataset/STRESS').glob('S*/*.csv')) if path.parent.name in SUBJECTS}
    summary=dict(purpose='Exploratory simulation stress testing, not SAD detection',clinical_validation=False,
        split=split,independent_source_participants=17,run_seeds=RUN_SEEDS,blocks=BLOCK_COUNTS,
        validation_rows=len(va),evaluation_rows=len(evaluation),evaluation_already_inspected=True,
        threshold_rule='Max sensitivity subject to validation row FPR <= 0.05, refit for each candidate',
        learning=learning.to_dict('records'),comparison=comparisons.to_dict('records'),
        change_strength=strengths.to_dict('records'),false_flags=errors.to_dict('records'),quality_checks=checks,
        source='Hongn et al. (2025), PhysioNet wearable-device-dataset 1.0.1, DOI:10.13026/he0v-tf17; ODC-By',
        source_hashes=source_hashes,
        limitations=['Reference windows unlabelled, not confirmed healthy.',
          'All cognition and injected changes are invented. Recognition mostly reflects generator assumptions.',
          'Same 17 real source participants; more rows do not add independent people.',
          'Evaluation source participants already inspected previously: exploratory reuse, not an untouched final test.',
          'Seed ranges are not confidence intervals or clinical uncertainty estimates.',
          'Quality metadata are supplied explicitly; automated non-wear/tapping detection is not established.'])
    (out/'experiment_results.json').write_text(json.dumps(summary,indent=2,allow_nan=False))
    large=learning[learning.train_rows==learning.train_rows.max()]
    strength_group=strengths.groupby('strength').sensitivity.mean()
    text=f'''# SAM presentation evidence: measured simulation experiments

These figures describe synthetic scenario recognition, not SAD or delirium detection accuracy.

## What was measured

Real wrist recordings from 17 source participants were reused: 9 training, 4 validation and 4 exploratory evaluation. The evaluation participants had already been inspected in earlier work; these are exploratory results, not a new final test. Skin temperature is peripheral, not core body temperature. Initial reference windows are unlabelled, not confirmed healthy. Cognition, scenario labels and injected changes are invented.

Training sizes were {', '.join(str(n) for n in sorted(learning.train_rows.unique()))} rows, all from the SAME 9 training source participants. Three generator seeds were used. Thresholds were chosen only on validation data, aiming for at most 5% validation negative-row flags. Evaluation has {len(evaluation)} correlated synthetic rows, not {len(evaluation)} independent patients.

## Measured findings

At the largest training size, mean detection of standard scripted changes was {large.sensitivity.mean():.1%}; negative-scenario flags averaged {large.false_positive_fraction.mean():.1%}. At 0.25× injected feature change, mean positive-row flagging was {strength_group.loc[.25]:.1%}; at 1× it was {strength_group.loc[1]:.1%}. These multipliers are engineering transformations, not clinical severity levels.

In this run, increasing synthetic training rows sixfold improved mean detection by only about one percentage point. Watch-only models remained weak; a validation false-positive constraint did not guarantee the same error rate on evaluation data.

Increasing invented training rows is not guaranteed to improve performance. Report the learning curve as observed, including plateaus or decreases. The input comparison tests whether success is dominated by synthetic cognition rather than watch signals. The false-flag graph shows which ordinary-use contexts overlap with the invented positive patterns. Mean task-noise flagging was {errors[errors.scenario.eq("task_noise")].flag_fraction.mean():.1%}.

All {len(checks)} explicit quality-gate cases produced their expected allow/notice result. This tests deterministic software behaviour, not learned misuse detection. Metadata such as watch removed, rapid tapping and task incomplete are supplied by the test, not inferred from sensors.

## Presentation wording

“We compared input sets and varied synthetic training size and change strength using real wrist recordings as a source of variation. The model recognises our scripted patterns, but subtle changes and ordinary task variation expose limitations. This experiment does not demonstrate SAD diagnosis or clinical accuracy.”

## Figure notes

1. Learning curve: same evaluation set; lines are means and shading is min–max across 3 generator seeds. It is not a confidence interval.
2. Change strength: paired interpolation toward each unmodified feature vector. At multiplier 0 no injected change remains, even though the row retains its scripted-positive designation for comparison.
3. Input comparison: six predeclared candidates at the largest training size, seed 101. No evaluation-based winner selection.
4. False flags: negative-case row proportions, not hourly alarm rates. Whiskers show min–max across 3 seeds.

## Reproduce

Run the original train.py once to populate research-cache, then install matplotlib and run:

```powershell
$env:OMP_NUM_THREADS = "2"
python research/baseline_training/experiments.py --cache research-cache --output experiment-output
```

PNG files are slide-ready; SVG files are editable. CSV files and experiment_results.json retain measured outputs and provenance. No trained model was deployed.

Source attribution: Hongn, Bosch, Prado and Bonomini (2025), Wearable Device Dataset from Induced Stress and Structured Exercise Sessions, v1.0.1. https://physionet.org/content/wearable-device-dataset/1.0.1/ DOI:10.13026/he0v-tf17. Database: ODC-By. No clinical labels were added to the source records.
'''
    (out/'READ_ME_FIRST.md').write_text(text)
    print(json.dumps(dict(standard_detection_mean=float(large.sensitivity.mean()),negative_flag_mean=float(large.false_positive_fraction.mean()),strength_detection_mean=strength_group.to_dict(),quality_cases=len(checks)),indent=2))

if __name__ == '__main__': main()
