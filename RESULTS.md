# Results: file design vs. chunk design

Measured on a laptop against the local Temporal dev server (`temporal server start-dev`), with SQLite standing in for a production database and a fake vendor answering each row 1–30 seconds after it's sent (1% failures). Each configuration ran three times on its own freshly generated 100,000-row file. Every run finished `COMPLETED`, no activity was retried, and the worker and vendor logged no warnings. The chunk-design and file-1000 numbers are from 2026-11-03, after the chunk design was changed to send one signal per chunk; file-5000 is from 2026-10-30 (the file design didn't change).

**What "actions" means here.** The numbers come from [`count-actions`](README.md#measuring-actions), which counts billable events in each run's history (the file workflow plus every chunk workflow) using the rules on the [Temporal Cloud Actions](https://docs.temporal.io/evaluate/cloud/actions) page. Heartbeats aren't recorded in history, so they're estimated from activity durations. Temporal Cloud's usage for two real runs matched these counts exactly for everything in history, and came within 3% for heartbeats; see [Checked against Temporal Cloud](#checked-against-temporal-cloud).

## Per 100,000-row file (median of 3 runs)

| Design                           | Batch / chunk size | Workflows | **Actions** | File workflow history | Largest chunk history | Wall clock |
| -------------------------------- | ------------------ | --------- | ----------- | --------------------- | --------------------- | ---------- |
| File                             | 1,000              | 1         | **408**     | 857 events            | –                     | 34.3 s     |
| File                             | 5,000              | 1         | **88**      | 275 events            | –                     | 33.7 s     |
| Chunk                            | 1,000              | 101       | **903**     | 368 events            | 29 events             | 33.6 s     |
| Chunk                            | 5,000              | 21        | **183**     | 116 events            | 29 events             | 33.4 s     |

Every run of a configuration produced the same action count.

### Where the actions go

| Design / size | Workflow starts | Child starts | Activities | Heartbeats (est.) | Timers | Signals |
| ------------- | --------------- | ------------ | ---------- | ----------------- | ------ | ------- |
| File / 1,000  | 1               | 0            | 205        | 200               | 1      | 1       |
| File / 5,000  | 1               | 0            | 45         | 40                | 1      | 1       |
| Chunk / 1,000 | 101             | 100          | 302        | 200               | 100    | 100     |
| Chunk / 5,000 | 21              | 20           | 62         | 40                | 20     | 20      |

The counts follow simple formulas, so any file size can be estimated:

- **File design: 8 + 4 per batch.** Each batch costs two activities (seed and submit) and two heartbeats. The 8 fixed actions are the workflow start, five one-off activities, one timer and one signal.
- **Chunk design: 3 + 9 per chunk.** Each chunk costs 2 for the child workflow, 3 activities, 2 heartbeats, 1 timer for the deadline wait and 1 signal. The 3 fixed actions are the file workflow's start and its two activities.
- **Wall clock is the same for every design** (about 33–34 seconds), because it's set by the vendor's 30-second answer time, not by Temporal.

**What changed in the chunk design.** It used to send each chunk a progress signal every 5 seconds while answers arrived, which cost 140 of 303 actions at chunk size 5,000 and made the count depend on how fast the vendor answered. Each chunk now gets one signal, when its last row is answered. That took chunk-5000 from 303 to 183 actions and chunk-1000 from 1,408 to 903. It also made the count fixed: the number of signals no longer depends on how long the vendor takes.

## Checked against Temporal Cloud

On 2026-10-30, file-1000 and chunk-5000 were run once each against a Temporal Cloud namespace, before the signal change. The worker and vendor ran on the laptop. The Cloud Usage dashboard's totals for that day, set against `count-actions` for the same two runs:

| Usage category                   | Cloud | `count-actions`                       | Difference |
| -------------------------------- | ----- | ------------------------------------- | ---------- |
| Workflows                        | 42    | 1 + 21 + 20 = 42                      | none       |
| Signals                          | 209   | 1 + 208 = 209                         | none       |
| Timers                           | 28    | 1 + 20 = 21                           | +7         |
| Activities (including heartbeats) | 515   | 267 scheduled + 240 heartbeats (est.) | +8         |

- **Workflows and signals match exactly.**
- **Heartbeats:** Cloud recorded 515 − 267 = 248, against an estimate of 240, 3% apart. Temporal says its own action metrics can differ from metered usage by up to 5%.
- **Timers:** the 7 extra are most likely from other long-running workflows in the same namespace, which set timers every day. Timers come straight from history, so ours are exact.
- **Per workflow:** the Cloud UI showed 208 billable actions for the file-1000 run, which is the 408 here minus the 200 estimated heartbeats. The UI's per-workflow figure leaves heartbeats out.

The Billing API reports namespace totals only (no breakdown by action type), so the dashboard is the finer of the two. A per-type, per-workflow-type breakdown is available from the `temporal_cloud_v1_billable_action_count` metric, but only while a run is happening.

### Cross-check with temporal-history-action-count

To check `count-actions` independently, the first run of each configuration on 2026-10-30 was also counted with the community tool [temporal-history-action-count](https://github.com/temporal-community/temporal-history-action-count). It's unofficial and reads one exported history at a time. The histories were exported with `scripts/export-histories.sh`: the parent plus every chunk workflow. These runs predate the signal change, so the chunk totals are the old ones.

| Run           | Community tool, all histories summed | Minus 1 per child | `count-actions` from history | Heartbeats (est.) | `count-actions` total |
| ------------- | ------------------------------------ | ----------------- | ---------------------------- | ----------------- | --------------------- |
| File / 1,000  | 208                                  | 208               | 208                          | 200               | 408                   |
| File / 5,000  | 48                                   | 48                | 48                           | 40                | 88                    |
| Chunk / 1,000 | 1,306                                | 1,206             | 1,206                        | 200               | 1,406                 |
| Chunk / 5,000 | 283                                  | 263               | 263                          | 40                | 303                   |

The two agree exactly on every event type in every run. They differ in two known ways:

- **Child workflows.** The tool counts each child as 2 actions on the parent's side, and a child's own history adds its start as 1 more, so summing every history gives 3 per child. `count-actions` counts 2 per child, which matches the docs' "Child Workflows cost 2 Actions". The "Minus 1 per child" column removes the extra one.
- **Heartbeats.** They aren't in history, so the tool doesn't count them. `count-actions` estimates them, and Temporal Cloud's usage confirmed the estimate (above).

## What we'd recommend

**The chunk design at 5,000 rows per chunk: 183 actions per 100,000-row file (measured).**

- It costs **55% fewer actions than the file design at batch size 1,000** (183 vs. 408). The file design is cheaper still at batch size 5,000 (88), but it still tracks the whole file as one unit.
- Each chunk is its own workflow with its own counter, deadline and outcome. A worker failure, a missed signal or a rerun affects 5,000 rows, not 100,000.
- The file workflow's history stays small (116 events), well inside a target of a few hundred events. The file design at batch size 1,000 doesn't meet it (857).
- The cost doesn't depend on the vendor. Each chunk gets exactly one signal however long the vendor takes to answer.
- No lost-signal guard is needed. Each chunk re-reads its rows from the database when it finishes, so a missed signal can only delay a chunk until the deadline; it can't produce a wrong count.

## Scaled to 1 million rows a day (estimate)

This is arithmetic on the medians above, not a measurement. It assumes every file is 100,000 rows (10 files a day) and a 30-day month. Multiply by your own daily volume:

| Design / size     | Per file | Per day (10 files) | Per month   |
| ----------------- | -------- | ------------------ | ----------- |
| File / 1,000      | 408      | 4,080              | 122,400     |
| File / 5,000      | 88       | 880                | 26,400      |
| Chunk / 1,000     | 903      | 9,030              | 270,900     |
| **Chunk / 5,000** | **183**  | **1,830**          | **54,900**  |

At Temporal Cloud's published starting rate of $50 per million actions, the recommended setup is about $2.75 a month in actions for every million rows a day, before plan minimums, discounts and storage.

### What would move these numbers

- **Retries cost one action each.** These runs had none. A vendor or database outage would add retries.
- **Heartbeats.** Long-running seed activities send more heartbeats than these sub-second ones did: about one more per 24 seconds of running time (80% of the 30-second heartbeat timeout).
- **Chunk size.** Fewer, larger chunks cost less; see the formulas above. Keep it under about 1,000 chunks per file, Temporal's guidance for children per parent.

## Common questions

**Q1. Where do I put my checkpoints?** The batch or chunk size is the checkpoint, and it's the main cost dial. At 100,000 rows, going from 1,000 to 5,000 rows per checkpoint cut actions by about 80% in both designs (408 → 88, 903 → 183) without changing wall-clock time. Within a checkpoint, the seed activity also heartbeats its progress every 200 rows, so a retry resumes partway through a chunk rather than starting over.

**Q2. Is the start and finish of one activity one action or several?** One. Scheduling an activity counts as one action, and finishing it adds nothing. Retries add one action each, and heartbeats that reach the server add one each. That's why each batch here costs about two actions plus its heartbeats.

**Q3. How much will it cost before running it?** About 183 actions per 100,000-row file for the recommended setup (measured), or about 55,000 actions a month for every million rows a day (estimate). The method is repeatable: run a sample file with `start --wait`, then `count-actions` on its workflow ID. Or use the formula: 3 + 9 per chunk.

**Q4. Can I replay just 20 of 100,000 records?** The POC doesn't include the retry step, but the data it needs is all there: every row's state and error code is in the database, and each chunk returns its failure counts by error code. A retry step would select the failed rows for one error code and resubmit only those.

**Q5. What happens if a worker dies mid-file?** Verified by unit test rather than by killing a running worker: a retried seed activity resumes from its last heartbeat, and every write is safe to repeat. With a 30-second heartbeat timeout, Temporal notices a dead worker within 30 seconds rather than waiting out the 10-minute activity timeout.

**Q6. Replay vs. retry.** Replay is a worker rebuilding a workflow's state from its history after a restart: completed activities aren't run again, and replay isn't billed. A retry is an activity actually running again after a failure or timeout: it does the work again and costs one action. That's why every activity here is written to be safe to run twice.

## Not covered by these runs

- **File sizes:** only 100,000-row files. 1,000- and 10,000-row files, and batch size 1, weren't run.
- **The vendor:** it answers in 1–30 seconds; the real one answers in minutes. That doesn't change the action counts, but it does change wall-clock time.
- **Infrastructure:** one worker process on a laptop, with SQLite. Temporal Cloud was used for two runs on 2026-10-30, before the signal change; the current chunk code has only run on the local dev server. No production database, object storage or message queue.

To reproduce, see [Measuring actions](README.md#measuring-actions).
