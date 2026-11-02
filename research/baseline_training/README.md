# Baseline-relative scenario training

Offline research experiment. **The target is an injected scenario change, not delirium, SAD, sepsis, or a clinical probability.** No watch display, dashboard alert, or production inference has been connected to the exported model.

## What was run

The script downloads actual heart rate, peripheral skin temperature and wrist acceleration from 17 participants in the STRESS arm of Hongn et al. (2025), *Wearable Device Dataset from Induced Stress and Structured Exercise Sessions*, version 1.0.1, DOI:10.13026/he0v-tf17, https://physionet.org/content/wearable-device-dataset/1.0.1/. Attribution: Andrea Hongn, Facundo Bosch, Lara Prado and Paula Bonomini. The database is under the Open Data Commons Attribution License (ODC-By); the source LICENSE.txt and README.txt are cached with downloads. Original publication: DOI:10.1038/s41597-025-04845-9.

S02 is excluded because the source documents duplicated signals. Only S01 and S03–S18 are used; the other stage's recordings, including documented covered sensors and split sessions, are outside this experiment. Source files are cached, and SHA-256 hashes are recorded in results.json.

Five initial aligned minute windows form an **unlabelled engineering reference**. They are not assumed clinically normal or confirmed non-delirious. This does not change the application's rule that clinical baseline comparisons require explicitly confirmed baseline periods. Real source stress/rest segments are not relabelled as delirium or health.

Sensor modifications, cognitive accuracy, reaction times, activity/rest contexts and all target labels are scripted assumptions. Changes overlap with benign task variation deliberately. The source wrist device is Empatica E4, not Galaxy Watch. The trailing gravity removal and minute medians approximate research features; they do not reproduce the watch's MovementProcessor.

## Experiment design

- Source participant split: 9 training, 4 validation, 4 test. Every source participant and every derivative scenario remains within one split.
- 24 repetitions of nine scenarios per participant: unmodified recording, activity context, rest context, temperature context, task noise, reduced activity with cognitive change, increased activity with cognitive change, cognitive change alone, and mixed change.
- 1,944 training, 864 validation and 864 held-out synthetic rows. Another 864 rows test weaker injected changes, using the same four held-out participants; this is a stress test, not another independent cohort.
- Six candidates: logistic regression and modest gradient boosting, each using watch only, synthetic cognition only, or both.
- Model and operating threshold selected on validation only, maximising scripted-change sensitivity subject to at most 5% validation row false positives. A classifier with no acceptable positive predictions can be disabled.
- Test data opened only after selection. Subject bootstrap intervals are exploratory: four held-out real participants cannot support a stable population estimate.
- Only the five feature columns enter models. Subject IDs, scenario names, provenance and labels are excluded.

## Observed results

| Validation input/model | Scripted-change sensitivity | Row false-positive fraction |
|---|---:|---:|
| Watch only / logistic | 13.3% | 5.0% |
| Watch only / boosting | 18.5% | 5.0% |
| Synthetic cognition only / logistic | 89.3% | 4.8% |
| Synthetic cognition only / boosting | 90.4% | 5.0% |
| Both / logistic | 90.4% | 4.6% |
| Both / boosting (selected) | 91.4% | 5.0% |

The selected model detected 94.8% of stronger held-out scripted changes, with 5.2% false-positive rows. With weaker changes, detection fell to 64.1% and false-positive rows rose to 6.9%. These are synthetic recognition results, **not SAD sensitivity/specificity**. Scores must not be displayed as SAD probabilities.

In stronger held-out cases, 26.0% of benign task-noise rows triggered a flag. All four source participants had at least one false flag across their many negative counterfactual scenarios. This is not a real patient false-alarm rate or an hourly rate. It demonstrates why a low row error rate alone is inadequate.

The cognition-only comparison shows that high scores largely follow the invented task degradation. The dataset contains no reference delirium assessments and cannot establish a useful SAD model. The synthetic target fraction is 44.4%, an experimental balance, not a real prevalence. Weaker changes expose sensitivity to the simulation assumptions.

## Reproduce locally

From this repository's root, using Python 3.11 or later:

```powershell
python -m venv .venv-training
.\.venv-training\Scripts\Activate.ps1
python -m pip install -r research/baseline_training/requirements.txt
$env:OMP_NUM_THREADS = "2"
python research/baseline_training/train.py --cache research-cache --output research-output
python -m unittest discover -s research/baseline_training -p "test_*.py" -v
```

Outputs: results.json (source hashes, versions, participant split, validation comparisons, selected test metrics, scenario breakdown, exploratory intervals), scenario CSVs with provenance, and scenario_model.joblib. Only load joblib files you created or trust. Reproduction dependencies are pinned to the versions used in this run. Do not upload generated research scenarios to real patient records.

## Watch functionality covered

The new offline quality gate returns a notice instead of a model score when explicit metadata says watch removed, data stale, reference missing, task incomplete or rapid tapping. It also blocks nonfinite/missing/invalid readings and missing features. Bounds and freshness limits are engineering assumptions, not clinical thresholds. Wear/task metadata are supplied test inputs: no automatic non-wear or rapid-tap detection is claimed.

Nine new tests passed, including repeated three-column ACC timestamp parsing, reproducible generation, threshold handling and preventing inference for invalid inputs. Eight existing server tests also passed: no assumed normal baseline, descriptive movement metrics, input validation, HRV quality/gaps, atomic ingest validation and duplicate retry handling.

Android build, real watch collection, sensor accuracy, battery, on-device wear detection, Bluetooth reconnection and end-to-end Galaxy Watch → phone → SAM-Dash tests remain outstanding. The Python server tests concern the server in this watch repository, not the separate SAM-Dash Node backend.

## Next training stages

1. Collect labelled correct/incorrect use sessions on the actual watch; establish which wear, task and signal-quality metadata are really available.
2. Replace invented cognition distributions with consented task recordings, preserving incomplete/declined tasks separately from poor performance.
3. Expand simulator families with explicitly documented assumptions; freeze new validation/test source participants before further tuning. The current test cohort has now been inspected and must not be repeatedly used to select models.
4. Train and evaluate clinical SAD detection only with suitable timestamped clinician reference outcomes and a prespecified patient-level evaluation protocol. Do not infer clinical labels from heart rate or from the watch game itself.

## Presentation experiments

`experiments.py` adds a synthetic learning curve, paired change-strength stress test, six input/model comparisons, negative-scenario false flags and eight explicit quality-gate cases. It deliberately reuses the previously inspected source split and labels every result exploratory. More generated rows are not independent participants. It does not select a new model for deployment.

```powershell
python -m pip install -r research/baseline_training/experiment-requirements.txt
$env:OMP_NUM_THREADS = "2"
python research/baseline_training/experiments.py --cache research-cache --output experiment-output
```

Use the original `train.py` first if the cache is absent. Outputs include four PNG/SVG graphs, CSV measurements, source hashes and `READ_ME_FIRST.md` with presentation wording and limitations. Across all three seeds, the same fixed validation and exploratory evaluation banks are used. Seed min–max bands are not confidence intervals.
