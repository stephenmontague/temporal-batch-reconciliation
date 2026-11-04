# How the app works

## Summary

- A CSV file lands in an inbox. A Temporal workflow is started for it.
- The workflow splits the file into **batches**, saves every row to the database, queues the rows for a vendor, and waits.
- The vendor answers each row later. The app records the answers and signals the workflow.
- When every row is answered (or the deadline passes), the workflow sends rows that failed for a retryable reason again, then writes a report.

One workflow, `BasicWorkflow`, handles the whole file. That's Temporal's [Basic Workflow](https://docs.temporal.io/design-patterns/batch-processing-patterns#basic-workflow-single-tier-fan-out) batch pattern. [RESULTS.md](RESULTS.md) explains why this project uses it instead of fan-out with child workflows.

## The processes

Three jars, each run as its own process. They share one SQLite database (`data/batch-processing.db`) and the `batch-processing-core` library.

| Jar | What it does | Stands in for |
| --- | --- | --- |
| `basic-workflow-worker` | Runs `BasicWorkflow` and the activities | A Temporal worker |
| `fake-vendor` | Takes queued rows, answers them 1–30 s later, signals the workflow | The vendor + its callback |
| `batch-processing-cli generate` | Writes a fake CSV | An incoming file |
| `batch-processing-cli intake` | Watches `data/inbox` and starts a workflow for each new file | File arrival + trigger |
| `batch-processing-cli start` | Starts a workflow for one file by hand | – |
| `batch-processing-cli count-actions` | Counts billable actions for a finished run | – |

---

## Step 1: The apps start

1. [BasicWorkflowWorkerApplication](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/BasicWorkflowWorkerApplication.java) starts one Temporal worker on the `batch-processing-basic-workflow` task queue. Its `application.yml` lists the workflow class and the `reconciliationActivities` bean.
2. [FakeVendorApplication](fake-vendor/src/main/java/com/example/batchprocessing/vendor/FakeVendorApplication.java) starts [FakeVendor](batch-processing-core/src/main/java/com/example/batchprocessing/core/vendor/FakeVendor.java) and [VendorCallbackListener](batch-processing-core/src/main/java/com/example/batchprocessing/core/vendor/VendorCallbackListener.java).
3. [BatchProcessingCliApplication](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/BatchProcessingCliApplication.java) → [CommandLauncher](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/launch/CommandLauncher.java) reads the first argument (`start`, `intake`, …) and turns on the **Spring profile** with that name, so only that command's beans load.

## Step 2: A file is loaded

1. [InboxListener](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/start/InboxListener.java) checks `data/inbox` every 2 seconds.
2. For each new `.csv` it calls [FileIntake](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/start/FileIntake.java) `submit`.
3. (`start <file>` calls the same `submit` directly.)

## Step 3: The workflow starts

1. `FileIntake.submit` builds the workflow ID from the file name: `settlement-100000.csv` → `basic-workflow-settlement-100000`.
2. It starts **one** `BasicWorkflow`, by type name, on `batch-processing-basic-workflow`. The input carries only references: the file path, the file ID, the batch size, `--max-parallel`, the deadline, and which error codes are retryable (`--retry-codes`, `TIMEOUT` by default).
3. The type name comes from [WorkflowNames](batch-processing-core/src/main/java/com/example/batchprocessing/core/contract/WorkflowNames.java), which both the CLI and the worker compile against. The CLI never loads worker code.
4. If that ID is already running, Temporal refuses the duplicate. The same file can't be processed twice at once.

---

## Step 4: How the reconciliation steps work

The whole workflow is written out in one method: [BasicWorkflowImpl](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/workflow/impl/BasicWorkflowImpl.java)`.reconcile`. Read it top to bottom; each step has a short comment ending in its activity count, like `[1 activity: CountRows]` or `[1 activity per batch: SeedBatch]`, so you can count activities as you go: 6 per file, plus 2 per batch. Every activity call goes through the `activities` stub. The only other method is `runCapped`, the "at most 10 at a time" loop that the save and send steps share. The activities themselves are in [ReconciliationActivitiesImpl](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/impl/ReconciliationActivitiesImpl.java).

The counter key is the file ID: one pending counter for the whole file.

### 4.1 The steps, in order

Each step finishes before the next one starts.

**1. Count and split: `countRows`, once.** Counts the file's data rows, and the workflow cuts them into batches of `--batch-size` (1,000 by default). A 100,000-row file is 100 batches.

**2. Save: `seedBatch` per batch.** `runCapped` starts one `seedBatch` activity per batch and keeps at most `--max-parallel` (10) running: when the limit is reached, it waits for any one to finish before starting the next. Each `seedBatch` ([RowLoader](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/RowLoader.java)) opens the CSV, skips to the batch's first row, parses and validates every row, and inserts them 200 at a time. New rows become `SEEDED`, broken rows `INVALID`. After each 200-row insert it heartbeats a checkpoint, so a retry resumes partway through. Rows that already exist are skipped, so re-seeding is safe. The step ends when **every** batch has been seeded.

**3. Start counter: `startCounter`, once.** Counts the file's `SEEDED` rows and stores that number in `pending_counter`, together with this workflow's ID, so the vendor knows whom to signal at zero. It runs only after all seeding is done, so the count is complete. Run it again and it keeps the existing count.

**4. Send: `submitBatch` per batch.** Again at most 10 at a time. Each call moves the batch's `SEEDED` rows onto the `outbound_queue` and marks them `SUBMITTED`. From this moment the vendor can pick them up, and every answer it sends back lowers the counter.

**5. Lost-signal check: `getPending`, once.** Reads the counter. If it's already zero, for example because the vendor was fast or the signal was lost, the workflow skips the wait.

**6. Wait, with no activity.** `Workflow.await(timeLeft, allDone)` parks the workflow until the `allDone` signal flips the flag or the deadline passes. This is a durable timer: no worker thread or activity slot is held while waiting, and a worker restart doesn't lose it.

**7. Sweep: `markTimeouts`, once.** Every row still `SEEDED` or `SUBMITTED` becomes `TIMED_OUT`. If the vendor answered everything, this changes nothing.

**8. Retry failed rows: `retryFailedRows`, once, retried by Temporal.** See Step 5.

**9. Report: `writeReport`, once.** Writes `data/reports/<fileId>-report.csv`, one line per row with its final state and error code.

### 4.2 Where the parallelism comes from

Take a 100,000-row file in 100 batches, with the defaults: `--max-parallel=10` and 20 activity slots on the worker.

```
BasicWorkflow
  seed     ██████████ ██████████ ... ██████████     100 seedBatch, at most 10 at a time
  counter  ▌                                         1 startCounter, after ALL 100 are seeded
  submit   ██████████ ██████████ ... ██████████     100 submitBatch, at most 10 at a time
  wait     ──────────────────────────────────▶       one wait for the whole file
  sweep    ▌                                         1 markTimeouts
  retry    ▌ (· 30 s · ▌ · 60 s · ▌ · 2 min · ▌)     1 retryFailedRows, plus up to 3 retries if rows fail again
  report   ▌                                         1 writeReport
```

`--max-parallel` paces how hard the workflow pushes the database and the vendor. Nothing is submitted until every batch is seeded, because the single counter needs the full count first. The vendor's answer time (1–30 s) sets the overall pace, so a 100,000-row file takes well under a minute.

## Step 5: Retrying failed rows

The vendor answers asynchronously, so a failure isn't an exception in the workflow. It's an answer written to the database later: the row becomes `FAILED` with an error code. The fake vendor fails about 1% of rows, half with `TIMEOUT` and half with `ACCOUNT_LOCKED`. A timeout is usually temporary, so it's worth trying again. A locked account needs a person.

**One activity, retried by Temporal.** After the sweep, the workflow calls `retryFailedRows(fileId, retryableErrorCodes)` once. The activity's own retry policy, `FAILED_ROW_RETRY` in [ReconciliationActivityOptions](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivityOptions.java), does the trying again: 4 attempts in all, 30 s, then 60 s, then 2 min apart.

**What one attempt does** ([FailedRowRetrier](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/FailedRowRetrier.java)):

1. **Resend.** `TxnStore.resendRetryableFailures` finds the file's `FAILED` rows whose error code is retryable, plus any `RETRYING` row an earlier attempt sent but never heard back about. It moves them to `RETRYING` and puts them back on the outbound queue.
2. **Wait.** It checks the database every second until none of them is `RETRYING`, heartbeating while it waits. The fake vendor answers within 30 s; if it hasn't answered within the attempt's 2-minute Start-To-Close timeout, the attempt ends and the next one sends those rows again.
3. **Check.** If any resent row failed again with a retryable code, it throws a retryable `ROWS_STILL_FAILING` failure. Temporal waits out the backoff and runs the next attempt, which only sends what's still failing. Otherwise it returns how many rows it sent.

**The catch.** If the fourth attempt still fails, the activity call throws `ActivityFailure` in the workflow. The workflow's `retryFailedRows` step catches it, logs it, sweeps any row still `RETRYING` to `TIMED_OUT`, and carries on. Those rows stay `FAILED` in the report, and the file still finishes. One bad vendor day doesn't fail the whole file.

**Only references cross the workflow boundary.** The workflow passes the file ID and the list of codes, and gets back counts. It never sees row IDs, so a file with 40,000 failed rows doesn't fill its history.

**The counter isn't involved.** `applyAnswers` lowers the pending counter only for first answers (`SUBMITTED` rows). A `RETRYING` row's answer just updates the row, because the retry activity does its own waiting. So the vendor never sends a second `allDone` signal.

The result's `retry` field says how it went: `NOT_NEEDED` (nothing had a retryable code, or `--retry-codes=` was empty), `SUCCEEDED` with the number of attempts, or `GAVE_UP` after 4.

## Step 6: The vendor answers

This runs in the `fake-vendor` process, outside Temporal.

1. [FakeVendor](batch-processing-core/src/main/java/com/example/batchprocessing/core/vendor/FakeVendor.java) polls the vendor queue (`TxnStore.takeOutbound`).
2. It schedules an answer for each row 1–30 s later. About 1% are failures (`ACCOUNT_LOCKED`, `TIMEOUT`). Resent rows are answered the same way.
3. When answers are due, it hands them to [VendorCallbackListener](batch-processing-core/src/main/java/com/example/batchprocessing/core/vendor/VendorCallbackListener.java) `onAnswers`.
4. `onAnswers` → `TxnStore.applyAnswers`: rows become `COMPLETED` or `FAILED`, and the file's counter goes down for each first answer.
5. When the counter hits 0, the listener sends one `allDone` signal to the workflow.
6. **How it knows which workflow:** `startCounter` saves the workflow's ID on the counter row. The listener reads it from there and signals by ID and signal name, using an untyped workflow stub.
7. The signal wakes the waiting workflow (Step 4.1, step 6).

## Step 7: Finish

1. The workflow writes the report and returns a `FileResult`: `COMPLETED` if nothing timed out in the main pass, otherwise `TIMED_OUT`, plus how the retry went.
2. `start --wait` prints that result, including a `Retry:` line.

---

## A row's life

`SEEDED` (or `INVALID` if it failed validation) → `SUBMITTED` → `COMPLETED` / `FAILED` / `TIMED_OUT`

A `FAILED` row with a retryable error code goes `FAILED` → `RETRYING` → `COMPLETED` / `FAILED`, once per retry attempt. ([TxnState](batch-processing-core/src/main/java/com/example/batchprocessing/core/store/TxnState.java))

## Classes (one line each)

In `basic-workflow-worker`, implementation classes sit in an `impl/` folder next to their interfaces:

| Class | Job |
| --- | --- |
| [BasicWorkflow](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/workflow/BasicWorkflow.java) / [impl/BasicWorkflowImpl](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/workflow/impl/BasicWorkflowImpl.java) | The workflow. Every step, readable top to bottom, including the catch around the failed-row retry. |
| [ReconciliationActivities](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivities.java) / [impl/ReconciliationActivitiesImpl](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/impl/ReconciliationActivitiesImpl.java) | The only activities. |
| [ReconciliationActivityOptions](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivityOptions.java) | Timeouts and retry policies for every activity call, including the limited one for `retryFailedRows`. |
| [FailedRowRetrier](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/FailedRowRetrier.java) | One retry attempt: resend retryable failures, wait for the answers, throw if any fail again. |
| [CsvRowParser](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/CsvRowParser.java) | Parses and validates one row. |
| [RowLoader](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/RowLoader.java) | Reads a batch of CSV rows; saves in groups of 200 with a heartbeat after each, so a retry resumes. |
| [ReportWriter](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/ReportWriter.java) | Writes the per-row CSV report. |

In `batch-processing-core`, shared with the vendor and the CLI (or, for `CsvGenerator` and the fake vendor, with the worker's tests):

| Class | Job |
| --- | --- |
| [TxnStore](batch-processing-core/src/main/java/com/example/batchprocessing/core/store/TxnStore.java) | All database reads and writes. Every write is safe to repeat. |
| [CsvGenerator](batch-processing-core/src/main/java/com/example/batchprocessing/core/csv/CsvGenerator.java) | Makes fake test files (`generate`). |
| [WorkflowNames](batch-processing-core/src/main/java/com/example/batchprocessing/core/contract/WorkflowNames.java) | The workflow, signal, and query names the apps use to reach each other. |
| `store/entity/`, `store/repository/` | The three tables (`txn`, `pending_counter`, `outbound_queue`) and their Spring Data interfaces. |
| `dto/` | Records passed between apps: the workflow's input and result, and what the store and vendor exchange. |
| `config/` | Shared settings and persistence. |

In `batch-processing-cli`:

| Class | Job |
| --- | --- |
| [ActionCounter](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/actions/ActionCounter.java) | Reads workflow histories and counts billable actions (`count-actions`). |
