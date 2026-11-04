# How the app works

## Summary

- A CSV file lands in an inbox. A Temporal workflow is started for it.
- The workflow saves every row to the database, queues the rows for a vendor, and waits.
- The vendor answers each row later. The app records the answers and signals the workflow.
- When every row is answered (or the deadline passes), the workflow writes a report.
- There are two versions of the workflow: the **file design** (one workflow per file) and the **chunk design** (one parent workflow plus one child workflow per chunk). You pick one with `--design`.

## The processes

One jar, run as separate processes. The first argument picks the role.

| Command         | What it does                                                 | Stands in for             |
| --------------- | ------------------------------------------------------------ | ------------------------- |
| `generate`      | Writes a fake CSV                                            | An incoming file          |
| `intake`        | Watches `data/inbox` and starts a workflow for each new file | File arrival + trigger    |
| `start`         | Starts a workflow for one file by hand                       | –                         |
| `worker`        | Runs the workflows and activities                            | Temporal worker processes |
| `vendor`        | Fake vendor: takes queued rows, answers them 1–30 s later    | The vendor + its callback |
| `count-actions` | Counts billable actions for a finished run                   | –                         |

---

## Step 1: The app starts

1. [ReconApplication](src/main/java/com/example/recon/ReconApplication.java) `main` → [CommandLauncher](src/main/java/com/example/recon/cli/CommandLauncher.java) `launch`.
2. `launch` reads the first argument (`worker`, `vendor`, …) and turns on the **Spring profile** with that name.
3. The profile decides which beans load:
   - `worker` → Temporal starts workers on two task queues: `reconciliation-file` (file design) and `reconciliation-chunk` (chunk design). The workflows and activities are found by their `@WorkflowImpl` / `@ActivityImpl` annotations.
   - `vendor` → [FakeVendor](src/main/java/com/example/recon/service/FakeVendor.java) and [VendorCallbackListener](src/main/java/com/example/recon/listener/VendorCallbackListener.java) start.
   - `intake` → [InboxListener](src/main/java/com/example/recon/listener/InboxListener.java) starts.
4. Every process shares the same SQLite database (`data/recon.db`).

## Step 2: A file is loaded

1. [InboxListener](src/main/java/com/example/recon/listener/InboxListener.java) checks `data/inbox` every 2 seconds.
2. For each new `.csv` it calls [FileIntake](src/main/java/com/example/recon/service/FileIntake.java) `submit`.
3. (`start <file>` calls the same `submit` directly.)

## Step 3: The workflow starts

1. `FileIntake.submit` builds the workflow ID from the file name: `settlement-100000.csv` → `recon-settlement-100000`.
2. It starts **one** workflow on the chosen design's task queue:
   - file design → `ReconciliationFileWorkflow`
   - chunk design → `ChunkedFileWorkflow`
3. If that ID is already running, Temporal refuses the duplicate. The same file can't be processed twice at once.

---

## Step 4a: File design (one workflow per file)

One workflow: [ReconciliationFileWorkflowImpl](src/main/java/com/example/recon/design/file/workflow/ReconciliationFileWorkflowImpl.java). Activities: [ReconciliationActivitiesImpl](src/main/java/com/example/recon/design/file/activity/ReconciliationActivitiesImpl.java).

| #   | Workflow step         | Activity method | What happens                                                           |
| --- | --------------------- | --------------- | ---------------------------------------------------------------------- |
| 1   | Count                 | `countRows`     | Counts data rows in the file.                                          |
| 2   | Split                 | (none)          | Splits the file into batches (default 1,000 rows).                     |
| 3   | Seed (10 at a time)   | `seedChunk`     | Reads each batch, validates rows, saves them as `SEEDED` or `INVALID`. |
| 4   | Set counter           | `setCounter`    | Creates one counter for the file: "N rows waiting on vendor".          |
| 5   | Submit (10 at a time) | `submitBatch`   | Puts each batch's rows on the vendor queue. Rows become `SUBMITTED`.   |
| 6   | Lost-signal check     | `getPending`    | If the counter already hit 0, skip the wait.                           |
| 7   | Wait                  | (none)          | Sleeps until the `allDone` signal arrives or the deadline passes.      |
| 8   | Sweep                 | `markTimeouts`  | Unanswered rows become `TIMED_OUT`.                                    |
| 9   | Report                | `writeReport`   | Writes `data/reports/<file>-report.csv`.                               |

## Step 4b: Chunk design (the proposed shape)

Two workflows. Activities for both: [ChunkActivitiesImpl](src/main/java/com/example/recon/design/chunk/activity/ChunkActivitiesImpl.java).

**Parent: [ChunkedFileWorkflowImpl](src/main/java/com/example/recon/design/chunk/workflow/ChunkedFileWorkflowImpl.java)** (one per file)

| #   | Step              | Activity method | What happens                                                                              |
| --- | ----------------- | --------------- | ----------------------------------------------------------------------------------------- |
| 1   | Count             | `countRows`     | Counts data rows.                                                                         |
| 2   | Start children    | (none)          | Starts one `ChunkWorkflow` per chunk (default 1,000 rows), all at once. Max 1,000 chunks. |
| 3   | Wait for children | (none)          | Each child returns its result automatically when it finishes.                             |
| 4   | Add up            | (none)          | Totals the children's results.                                                            |
| 5   | Report            | `writeReport`   | Writes the CSV report.                                                                    |

**Child: [ChunkWorkflowImpl](src/main/java/com/example/recon/design/chunk/workflow/ChunkWorkflowImpl.java)** (one per chunk, ID `recon-<file>-chunk-<n>`)

| #   | Step   | Activity method | What happens                                                                   |
| --- | ------ | --------------- | ------------------------------------------------------------------------------ |
| 1   | Stage  | `stageChunk`    | Saves this chunk's rows, then creates **this chunk's** counter.                |
| 2   | Submit | `submitChunk`   | Puts the chunk's rows on the vendor queue.                                     |
| 3   | Wait   | (none)          | Sleeps until the `allDone` signal arrives or the deadline passes.              |
| 4   | Finish | `finishChunk`   | Marks unanswered rows `TIMED_OUT`, counts the chunk's rows by state and error. |
| 5   | Return | (none)          | Returns the counts to the parent.                                              |

**The difference in one line:** the file design tracks 100,000 rows as one unit; the chunk design tracks each chunk on its own, so a problem affects one chunk, not the file.

---

## Step 5: The vendor answers

This runs in the `vendor` process, outside Temporal.

1. [FakeVendor](src/main/java/com/example/recon/service/FakeVendor.java) polls the vendor queue (`TxnStore.takeOutbound`).
2. It schedules an answer for each row 1–30 s later. About 1% are failures (`ACCOUNT_LOCKED`, `TIMEOUT`).
3. When answers are due, it hands them to [VendorCallbackListener](src/main/java/com/example/recon/listener/VendorCallbackListener.java) `onAnswers`.
4. `onAnswers` → `TxnStore.applyAnswers`: rows become `COMPLETED` or `FAILED`, and each affected counter goes down.
5. When a counter hits 0, the listener sends one `allDone` signal to the workflow that owns it: the file workflow (file design) or that chunk's workflow (chunk design).
6. **How it knows which workflow:** when an activity creates a counter (`setCounter` / `stageChunk`), it saves its own workflow ID on the counter row. The listener reads it from there and signals by ID and signal name, using an untyped workflow stub.
7. The signal wakes the waiting workflow (Step 4a #7 / Step 4b child #3).

## Step 6: Finish

1. The workflow sweeps anything still unanswered to `TIMED_OUT`.
2. It writes the report and returns a `FileResult`: `COMPLETED` if nothing timed out, otherwise `TIMED_OUT`.
3. `start --wait` prints that result.

---

## A row's life

`SEEDED` (or `INVALID` if it failed validation) → `SUBMITTED` → `COMPLETED` / `FAILED` / `TIMED_OUT`

## Helper classes (one line each)

| Class                                                                       | Job                                                                                                   |
| --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [TxnStore](src/main/java/com/example/recon/service/TxnStore.java)           | All database reads and writes. Every write is safe to repeat.                                         |
| [RowLoader](src/main/java/com/example/recon/service/RowLoader.java)         | Reads and validates CSV rows; saves in groups of 200 with a heartbeat after each, so a retry resumes. |
| [ReportWriter](src/main/java/com/example/recon/service/ReportWriter.java)   | Writes the per-row CSV report.                                                                        |
| [FileIntake](src/main/java/com/example/recon/service/FileIntake.java)       | Starts the right workflow for a file.                                                                 |
| [CsvGenerator](src/main/java/com/example/recon/service/CsvGenerator.java)   | Makes fake test files (`generate`).                                                                   |
| [ActionCounter](src/main/java/com/example/recon/service/ActionCounter.java) | Reads workflow histories and counts billable actions (`count-actions`).                               |
| `repository/`                                                               | Spring Data interfaces Hibernate uses to query each table.                                            |
| `entity/`                                                                   | The three tables: `txn` (rows), `pending_counter`, `outbound_queue`.                                  |
| `dto/`                                                                      | Records passed between workflows, activities and services.                                            |
| `config/`                                                                   | Settings from `application.yml` and the beans each profile needs.                                     |
| `view/`                                                                     | Prints results and Temporal UI links to the console.                                                  |
