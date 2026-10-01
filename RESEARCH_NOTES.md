# Research design notes

## Suggested first population

For initial feasibility/model-development work, a defensible starting cohort is consecutive acute geriatric/general-medical inpatients aged 65+ (with protocol-specific inclusion/exclusion criteria), enrolled early rather than only after confusion is suspected. This yields controls, incident delirium, prevalent delirium, and recovery trajectories.

## Reference assessment

Use a validated clinical delirium reference assessment at protocol-defined intervals and record exact timestamps. A watch-based game must not be treated as the reference diagnosis.

## Baseline handling

The first hours are **not** assumed to be normal. Baseline comparisons use only periods explicitly labelled `NON_DELIRIOUS_CONFIRMED`. The server also computes a short-term change index that does not require a long baseline.

## Suggested annotations

- `UNKNOWN`
- `NON_DELIRIOUS_CONFIRMED`
- `DELIRIUM_SUSPECTED`
- `DELIRIUM_CONFIRMED`
- `RECOVERY`
- 4AT score/time
- procedure/transport
- physiotherapy
- sleep/rest opportunity
- watch removed/charging
- sedating medication or major medication change (if approved for the study dataset)

## Movement endpoints

Preserve raw data when feasible but pre-specify interpretable derived features: RMS, MAD, SD, peak, jerk RMS, active fraction, immobile time, movement-bout count/duration, active-inactive transitions, day/night activity, rest fragmentation, M10, L5 and relative amplitude. Device-specific thresholds must be calibrated; published cut-offs from other accelerometers should not be copied without validation.

## Cognitive endpoints

Keep trial-level data. Useful outcomes include accuracy, omissions, commission errors, median reaction time and reaction-time variability. Record declined, timeout, abandoned, probable asleep and watch-not-worn separately where possible.

## Analysis warning

If the same participant contributes many windows, split train/test data by participant, not by window. Otherwise leakage can make model performance look unrealistically strong. Pre-specify how delirium onset time is assigned and avoid using post-onset data in an "early warning" label window.
