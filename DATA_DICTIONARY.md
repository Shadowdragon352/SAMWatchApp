# Data dictionary

Every event has: `event_id` (UUID), `study_id` (pseudonymous participant/study code), `device_id`, `ts_ms` (Unix milliseconds), `type`, `source`, and a JSON `payload`.

## Watch sensor events

### `movement_5s`
A nominal five-second window of gravity-removed wrist acceleration.

- `rms`: root-mean-square vector magnitude, m/s²
- `mad`: mean absolute deviation of vector magnitude, m/s²
- `sd`: standard deviation of vector magnitude, m/s²
- `peak`: maximum vector magnitude, m/s²
- `jerk_rms`: RMS of change in magnitude per second, m/s³
- `active_fraction`: fraction of samples whose magnitude is at/above `active_threshold_ms2`
- `transitions`: within-window crossings of the activity threshold
- `sample_count`, `duration_ms`
- `active_threshold_ms2`: the engineering threshold used for this window
- `raw`: optional array of `[ts_ms,x,y,z]`; disabled by default

The server additionally derives: observed/coverage time, mean/median RMS, one-hour active fraction, near-immobile-window fraction, transitions/hour, active-bout count/duration, one-minute movement RMSSD, mean jerk RMS, day/night activity ratio, M10, L5 and relative amplitude. These are descriptive research features, not diagnostic thresholds.

### `heart_rate`
- `bpm`
- `status`: SDK/device status when available
- `ibi_ms`: inter-beat intervals when Samsung Health Sensor SDK provides them
- `ibi_status`: corresponding IBI status values

The dashboard derives recent HRV RMSSD only from plausible IBI values (300–2000 ms) whose status is normal where a status is supplied.

### `temperature`
- `skin_temp_c`: Samsung object/peripheral skin temperature
- `ambient_temp_c`
- `status`

Skin temperature is not core body temperature and is not treated as a fever measurement.

### `ppg_summary`
Only created when PPG collection is enabled.
- `green_mean`, `green_sd`
- `red_mean`, `red_sd` when red is selected
- `ir_mean`, `ir_sd` when infrared is selected
- `valid_fraction`, `sample_count`
- `raw`: optional samples; disabled by default

PPG defaults to off because continuous optical sampling can increase battery use. The phone can enable green-only PPG or all three wavelengths, and can separately enable raw storage.

## Cognition events

### `cognition_prompt_outcome`
Values include `presented`, `started`, `declined`, `completed`. `prompt_id` links scheduled prompt slots; `session_id` links started sessions.

### `cognition_trial`
- `session_id`, `prompt_id`
- `task`: `attention` or `one_back`
- `trial_index`
- `expected`, `response`
- `correct`
- `rt_ms` (null for omission/timeout)

### `cognition_summary`
- `session_id`, `prompt_id`, `task`
- `accuracy`
- `median_rt_ms`
- `rt_sd_ms`
- `omissions`, `commissions`, `trials`

## Research annotations

Stored by the laptop server, not the watch. Supported kinds include `state`, `4at`, `procedure`, `watch`, `medication`, `note` and `other`.

State values recommended by the project are:
- `UNKNOWN`
- `NON_DELIRIOUS_CONFIRMED`
- `DELIRIUM_SUSPECTED`
- `DELIRIUM_CONFIRMED`
- `RECOVERY`

Only periods explicitly labelled `NON_DELIRIOUS_CONFIRMED` are allowed to contribute to the personalised reference calculation.

## Change indices

`acute_change.index` is a magnitude-only aggregation of robust z-scores comparing the most recent 30 minutes with approximately 1–6 hours earlier. Features are first combined within three domains (movement, physiology, cognition) and the available domains are then weighted equally, so a domain does not gain weight merely because it has more features. `confirmed_baseline_change.index` uses the same approach against explicitly labelled non-delirious reference data. Both are engineering/research descriptors from 0–100, **not probabilities, diagnostic scores or clinical alarms**.
