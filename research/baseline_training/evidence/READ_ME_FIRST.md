# SAM presentation evidence: measured simulation experiments

These figures describe synthetic scenario recognition, not SAD or delirium detection accuracy.

## What was measured

Real wrist recordings from 17 source participants were reused: 9 training, 4 validation and 4 exploratory evaluation. The evaluation participants had already been inspected in earlier work; these are exploratory results, not a new final test. Skin temperature is peripheral, not core body temperature. Initial reference windows are unlabelled, not confirmed healthy. Cognition, scenario labels and injected changes are invented.

Training sizes were 1944, 5832, 11664 rows, all from the SAME 9 training source participants. Three generator seeds were used. Thresholds were chosen only on validation data, aiming for at most 5% validation negative-row flags. Evaluation has 1728 correlated synthetic rows, not 1728 independent patients.

## Measured findings

At the largest training size, mean detection of standard scripted changes was 95.7%; negative-scenario flags averaged 5.8%. At 0.25× injected feature change, mean positive-row flagging was 10.3%; at 1× it was 95.7%. These multipliers are engineering transformations, not clinical severity levels.

In this run, increasing synthetic training rows sixfold improved mean detection by only about one percentage point. Watch-only models remained weak; a validation false-positive constraint did not guarantee the same error rate on evaluation data.

Increasing invented training rows is not guaranteed to improve performance. Report the learning curve as observed, including plateaus or decreases. The input comparison tests whether success is dominated by synthetic cognition rather than watch signals. The false-flag graph shows which ordinary-use contexts overlap with the invented positive patterns. Mean task-noise flagging was 27.4%.

All 8 explicit quality-gate cases produced their expected allow/notice result. This tests deterministic software behaviour, not learned misuse detection. Metadata such as watch removed, rapid tapping and task incomplete are supplied by the test, not inferred from sensors.

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
