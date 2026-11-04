# Batch Reconciliation POC on Temporal (Java, Spring Boot)

A file reconciliation pipeline built on Temporal and Spring Boot, as a reference for batch processing: how to checkpoint large files, wait for asynchronous answers, and measure what a design costs in Temporal Cloud actions. It runs two designs side by side on the same code: the **file design** (one workflow per file, with batch checkpoints) and the **chunk design** (one child workflow per chunk of rows). A `count-actions` command and an experiment script measure both. Measured results are in [RESULTS.md](RESULTS.md).

## The production flow, and what stands in for each part

```
 ┌────────────┐     ┌────────────┐     ┌─────────────────────────────────┐     ┌──────────────┐     ┌──────────┐
 │ CSV file   │ ──► │  trigger   │ ──► │ count → seed → counter → submit │ ──► │ outbound     │ ──► │downstream│
 │ arrives    │     │            │     │ → wait(all-done | deadline)     │     │ queue        │     │ service  │
 └────────────┘     └────────────┘     │ → mark stragglers → CSV report  │     └──────────────┘     └────┬─────┘
                                       └─────────────────────────────────┘ ◄── all-done signal ◄── answer listener
                                                                               (when pending counter hits 0)
```

| Production system | Stand-in here |
|---|---|
| A file arriving in storage | A `.csv` file in `data/inbox/` |
| Queue message or event that starts a run | [InboxListener](src/main/java/com/example/recon/listener/InboxListener.java) polls the inbox and starts one workflow per file (`intake` command) |
| Database rows with insert-if-absent writes | SQLite `data/recon.db` through Hibernate (Spring Data JPA), primary key on transaction ID; rows that already exist are skipped |
| Outbound queue to the downstream service | `outbound_queue` table |
| Downstream service + async answers (minutes later) | `vendor` command: answers each row 1–30 s later, with a configurable failure rate |
| Callback listener | [VendorCallbackListener](src/main/java/com/example/recon/listener/VendorCallbackListener.java), in the `vendor` process: applies answers, decrements the pending counter (per file or per chunk), and signals the workflow by ID |

## Two designs

Each design is self-contained, with its own packages, task queue and worker:

| | File design | Chunk design |
|---|---|---|
| Workflows | `design.file.workflow` | `design.chunk.workflow` |
| Activities | `design.file.activity` | `design.chunk.activity` |
| Task queue | `reconciliation-file` | `reconciliation-chunk` |
| Worker | its own worker, polling only that queue | its own worker, polling only that queue |

Neither design calls the other's workflows or activities. Both use the same services underneath: `RowLoader` (count, parse, validate, insert), `ReportWriter`, `TxnStore`, the vendor, and the listeners. In the Temporal UI, each task queue shows its own pollers, and each workflow shows which queue it ran on.

### File design (one workflow per file)

`ReconciliationFileWorkflow` ([interface](src/main/java/com/example/recon/design/file/workflow/ReconciliationFileWorkflow.java), [implementation](src/main/java/com/example/recon/design/file/workflow/ReconciliationFileWorkflowImpl.java)):

1. **countRows**: count the data rows in the file.
2. **seedChunk × N**: parse, validate, and persist each chunk of `batchSize` rows. Runs in parallel, at most `maxParallel` at a time. Rows that already exist are skipped, so a re-parse is safe. Heartbeats its offset, so a retry resumes where the last attempt stopped.
3. **setCounter**: create the file's pending counter from the seeded rows. This finishes before anything is sent.
4. **submitBatch × N**: move seeded rows onto the outbound queue (`SEEDED → SUBMITTED`), parallel and capped.
5. **Wait** for the all-done signal or the deadline. The deadline is measured from when the run started, so a worker outage counts against it. Before waiting, the workflow reads the live counter; if it is already zero (the signal was lost), it skips the wait.
6. **markTimeouts**: any row still waiting on the vendor becomes `TIMED_OUT`.
7. **writeReport**: per-row CSV in `data/reports/<fileId>-report.csv`.

Outcome is `COMPLETED` if the sweep found nothing, otherwise `TIMED_OUT`.

Row states: `SEEDED → SUBMITTED → COMPLETED | FAILED | TIMED_OUT`, plus `INVALID` for rows that fail validation (bad amount, currency, or date).

Design rules this follows:
- Only references (file path, file ID, offsets) go through the workflow. Row data stays in the database.
- The workflow decides; activities do the I/O. No clocks, randomness, or I/O in workflow code.
- Every activity is safe to run twice.
- Seed and submit get a 10-minute Start-To-Close and a 30-second heartbeat timeout. Everything else gets 60 seconds.

### Chunk design

`ChunkedFileWorkflow` ([implementation](src/main/java/com/example/recon/design/chunk/workflow/ChunkedFileWorkflowImpl.java)) starts one `ChunkWorkflow` child ([implementation](src/main/java/com/example/recon/design/chunk/workflow/ChunkWorkflowImpl.java)) per chunk of `batchSize` rows, all at once, and adds up their results:

```
ChunkedFileWorkflow          recon-<fileId>
 ├─ countRows                activity
 ├─ ChunkWorkflow × N        child, id recon-<fileId>-chunk-<n>
 │   ├─ stageChunk           parse, validate, insert, heartbeat; creates this chunk's pending counter
 │   ├─ submitChunk          put the chunk's rows on the outbound queue
 │   ├─ wait                 for the allDone signal (count reached 0), or the file's deadline
 │   └─ finishChunk          mark this chunk's stragglers TIMED_OUT, count its rows by state
 └─ writeReport              activity
```

What changes compared with the file design:
- **Each chunk tracks itself.** Every row records its chunk in a `counterKey` column, and each chunk has its own pending counter, so a worker failure, a deadline, or a rerun affects one chunk, not the file.
- **One signal per chunk.** The callback listener writes every answer to the database straight away and sends a chunk one `allDone` signal when its count reaches zero, the same as the file design does for the whole file.
- **No lost-signal guard.** `finishChunk` re-reads the chunk's rows from the database, so a missed signal can only make a chunk wait until its deadline; it can't produce a wrong result.
- **Every chunk waits at the same time,** so the deadline isn't limited by a concurrency window. The file workflow refuses more than 1,000 chunks, the Temporal guidance for children per parent.

Both designs share the row loading ([RowLoader](src/main/java/com/example/recon/service/RowLoader.java)), the report ([ReportWriter](src/main/java/com/example/recon/service/ReportWriter.java)), the store, and the vendor.

## How the Spring Boot app is put together

One jar, five commands. Each command runs Spring Boot with a profile of the same name, so a process only creates the beans it needs:

| Command / profile | What Spring creates |
|---|---|
| `worker` | Two Temporal workers in one process, one per design. `application-worker.yml` turns on the starter's auto-discovery, which registers each design's `@WorkflowImpl` and `@ActivityImpl` classes on that design's task queue (`recon.file-task-queue`, `recon.chunk-task-queue`). The activity beans come from [WorkerConfig](src/main/java/com/example/recon/config/WorkerConfig.java), which also caps concurrent activities per worker. |
| `vendor` | The fake vendor bean ([VendorConfig](src/main/java/com/example/recon/config/VendorConfig.java)), started and stopped with the context, plus `VendorCallbackListener`, which it hands each answer to. |
| `intake` | [IntakeCommand](src/main/java/com/example/recon/cli/IntakeCommand.java), which runs `InboxListener` until stopped. |
| `start` | `FileIntake` plus [StartCommand](src/main/java/com/example/recon/cli/StartCommand.java), which starts one file and exits. |
| `count-actions` | [CountActionsCommand](src/main/java/com/example/recon/cli/CountActionsCommand.java), which reads histories and prints an action estimate. |
| `generate` | [GenerateCommand](src/main/java/com/example/recon/cli/GenerateCommand.java) only. No database, no Temporal calls. |

Persistence is Spring Data JPA with Hibernate and its SQLite community dialect. [TxnStore](src/main/java/com/example/recon/service/TxnStore.java) is a transactional service over three repositories, one per table (`txn`, `file_counter`, `outbound_queue`). Every query is a derived Spring Data method except the deadline sweep, which is one bulk JPQL `update`. Hibernate creates the schema when the `worker` command starts (`ddl-auto: update` in `application-worker.yml`), so start the worker before the vendor on a fresh database. [PersistenceConfig](src/main/java/com/example/recon/config/PersistenceConfig.java) opens SQLite in WAL mode with a busy timeout and `BEGIN IMMEDIATE` transactions, because the worker and vendor processes write to the same file.

The `WorkflowClient` comes from the starter in every profile. Workers only start in the `worker` profile (`spring.temporal.start-workers` is `false` everywhere else). The workflow class is not a Spring bean: Temporal makes a new instance per run.

## Run it

You need Java 17+, Maven, and the [Temporal CLI](https://docs.temporal.io/cli).

```bash
temporal server start-dev
```

In a second terminal, build the jar:

```bash
mvn package
```

Then run the worker and the fake vendor, each in its own terminal:

```bash
java -jar target/recon-poc.jar worker
```

```bash
java -jar target/recon-poc.jar vendor
```

Generate a 100,000-row file and run it with a 2-minute deadline:

```bash
java -jar target/recon-poc.jar generate 100000
```

```bash
java -jar target/recon-poc.jar start data/inbox/settlement-100000.csv --deadline-seconds=120 --wait
```

Watch it in the UI at http://localhost:8233.

To mimic a file-arrival trigger instead, run the watcher, then drop or generate files into `data/inbox/`:

```bash
java -jar target/recon-poc.jar intake --deadline-seconds=120
```

To start over with an empty database:

```bash
rm -rf data/recon.db data/recon.db-wal data/recon.db-shm data/reports
```

### Commands

| Command | What it does |
|---|---|
| `generate <rows> [--out=FILE] [--invalid-rate=R] [--seed=N]` | Write a fake settlement CSV. Default invalid rate 0.001, seed 42. |
| `worker` | Run the Temporal worker. |
| `vendor` | Run the fake vendor and callback listener. |
| `intake [flags]` | Watch `data/inbox/` and start one workflow per new file. |
| `start <file> [flags] [--wait]` | Start the workflow for one file. |
| `count-actions <workflow-id> [--csv] [--label=TEXT]` | Estimate Temporal Cloud billable actions for a workflow and all its child workflows. |

Options for `intake` and `start`: `--design=file|chunk` (default `file`), `--batch-size=N` (default 1000; the chunk size in the chunk design), `--max-parallel=N` (default 10), `--deadline-seconds=N` (default 7200). Options take the `--name=value` form. Run the jar with no arguments to see this list.

The workflow ID is `recon-<file name>`. Starting a file whose workflow is still running is rejected, the same way a duplicate trigger message should be. Starting a file that already finished runs it again: seeding skips every row, the counter is already zero, and the run closes `COMPLETED` right away.

### Settings

Defaults live in [application.yml](src/main/resources/application.yml). Override any of them with an environment variable or a `--name=value` argument, for example `RECON_VENDOR_FAILURE_RATE=0` or `--recon.db=/tmp/recon.db`.

| Property | Environment variable | Default |
|---|---|---|
| `recon.db` | `RECON_DB` | `data/recon.db` |
| `recon.report-dir` | `RECON_REPORT_DIR` | `data/reports` |
| `recon.inbox` | `RECON_INBOX` | `data/inbox` |
| `recon.file-task-queue` | `RECON_FILE_TASK_QUEUE` | `reconciliation-file` |
| `recon.chunk-task-queue` | `RECON_CHUNK_TASK_QUEUE` | `reconciliation-chunk` |
| `recon.start.batch-size` / `max-parallel` / `deadline-seconds` | `RECON_START_BATCH_SIZE` etc. | `1000` / `10` / `7200` |
| `recon.vendor.min-delay-seconds` / `max-delay-seconds` | `RECON_VENDOR_MIN_DELAY_SECONDS` etc. | `1` / `30` |
| `recon.vendor.failure-rate` | `RECON_VENDOR_FAILURE_RATE` | `0.01` |
| `recon.vendor.error-codes` | `RECON_VENDOR_ERROR_CODES` | `ACCOUNT_LOCKED,TIMEOUT` |
| `recon.vendor.poll-millis` / `poll-batch` | `RECON_VENDOR_POLL_MILLIS` etc. | `500` / `2000` |
| `recon.start.design` | `RECON_START_DESIGN` | `file` |
| `recon.worker.max-concurrent-activities` | `RECON_WORKER_MAX_CONCURRENT_ACTIVITIES` | `20` |

The Temporal connection is the starter's `spring.temporal.*` settings. `application.yml` reads `TEMPORAL_ADDRESS` (default `localhost:7233`) and `TEMPORAL_NAMESPACE` (default `default`). For Temporal Cloud, turn on the `cloud` profile instead; see [Running against Temporal Cloud](#running-against-temporal-cloud).

## Tests

```bash
mvn test
```

| Test | What it checks |
|---|---|
| `completesWhenVendorAnswersEveryRow` | File design, 2,500 rows with real activities, SQLite, and the fake vendor. Ends `COMPLETED`; every valid row is `COMPLETED` or `FAILED`; replays the recorded history; checks `count-actions` finds exactly 15 activities. |
| `deadlineMarksStragglersTimedOut` | No vendor running. With time skipping, the deadline fires and every row is `TIMED_OUT`. |
| `rerunOfFinishedFileClosesImmediatelyViaCounterGuard` | A second run of a finished file closes `COMPLETED` in seconds, with no signal, instead of waiting for the deadline. |
| `seedingTwiceWritesEachRowOnce` | Running `seedChunk` twice writes nothing the second time. |
| `retryResumesFromHeartbeatedOffset` | A retry with heartbeat details at offset 600 only writes rows 600–999. |
| `chunksCompleteWhenVendorAnswersEveryRow` | Chunk design, 2,500 rows in 5 chunks. Ends `COMPLETED`; checks the action count (6 workflows, 5 child starts, 17 activities); replays the parent's and a chunk's history. |
| `deadlineTimesOutEveryChunk` | Chunk design with no vendor. With time skipping, every chunk hits the deadline and every row is `TIMED_OUT`. |
| `fileDesignProcessesAFileEndToEnd`, `chunkDesignProcessesAFileEndToEnd` | Boot the real Spring context (`worker` + `vendor` profiles) on the starter's embedded test server and run 2,000 rows to `COMPLETED` in each design. Fail if auto-discovery didn't register a workflow or activity. |
| `CommandLineArgsTest` | Argument parsing, option defaults, and that every command maps to its profile. |

The workflow tests use `TestWorkflowEnvironment` directly, without the full Spring app, so they can skip time. They get `TxnStore` from a small store-only Spring context ([TestStores](src/test/java/com/example/recon/service/TestStores.java)) with its own temp database.

## Measuring actions

`count-actions` reads a workflow's history and every child's history, and counts what Temporal Cloud bills for, using the [Temporal Cloud Actions](https://docs.temporal.io/evaluate/cloud/actions) page:

| History event | Actions |
|---|---|
| Workflow started (client, child, continue-as-new) | 1 |
| Child workflow start initiated (the parent's side) | 1, so a child costs 2 in total |
| Activity scheduled | 1, plus 1 per retry (from the started event's attempt number) |
| Timer started | 1 |
| Signal received, external signal sent | 1 each |
| Search attribute upsert, update accepted | 1 each |
| Local activity markers | 1 per workflow task |

Heartbeats aren't in history. The command estimates them for every activity that has a heartbeat timeout as `1 + floor(duration / (0.8 × heartbeat timeout))`, because the SDK sends the first heartbeat straight away and then throttles to 80% of the timeout. Queries aren't counted; the experiments send none.

The local dev server doesn't show billable actions, so these are estimates. To check them against Temporal Cloud, see [Running against Temporal Cloud](#running-against-temporal-cloud).

To run the experiments, start the worker and vendor against one database, then run the script (defaults: 100,000 rows, file and chunk designs at batch sizes 1,000 and 5,000, three runs each):

```bash
RECON_DB=data/experiments/recon.db java -jar target/recon-poc.jar worker
```

```bash
RECON_DB=data/experiments/recon.db java -jar target/recon-poc.jar vendor
```

```bash
scripts/run-experiments.sh
```

It writes every run to `data/experiments/results.csv` and the medians to `data/experiments/summary.csv`. `ROWS`, `RUNS`, `CONFIGS` (for example `"chunk:1000 chunk:10000"`) and `DEADLINE_SECONDS` override the defaults.

### Running against Temporal Cloud

The `cloud` profile ([application-cloud.yml](src/main/resources/application-cloud.yml)) connects with an API key, using the same environment variables as the Temporal CLI. Set them once in a terminal, along with a fresh database, and run every command from that terminal:

```bash
export TEMPORAL_ADDRESS=<namespace>.<account>.tmprl.cloud:7233
export TEMPORAL_NAMESPACE=<namespace>.<account>
export TEMPORAL_API_KEY=<your API key>
export SPRING_PROFILES_ACTIVE=cloud
export RECON_DB=data/cloud.db
```

Then run the usual commands. The worker and vendor still run on your machine; only Temporal is in the cloud. Start the worker first, so it creates the database tables:

```bash
java -jar target/recon-poc.jar worker
```

```bash
java -jar target/recon-poc.jar vendor
```

```bash
java -jar target/recon-poc.jar generate 100000 --out=data/cloud/chunk-5000.csv
```

```bash
java -jar target/recon-poc.jar start data/cloud/chunk-5000.csv --design=chunk --batch-size=5000 --deadline-seconds=600 --wait
```

```bash
java -jar target/recon-poc.jar count-actions recon-chunk-5000
```

`start` prints a link to the workflow in the Cloud UI. `scripts/run-experiments.sh` and `scripts/export-histories.sh` work the same way, because the `temporal` CLI reads the same variables. Keep the API key in the environment only; don't put it in a file in this repository.

To check `count-actions` against what Temporal Cloud records:

- **Everything except heartbeats:** open a workflow in the Cloud UI. Its event history shows a Billable Actions column and a total at the top, which leaves out heartbeats. Compare it with `count-actions` minus the heartbeat estimate. For the chunk design, the parent and each chunk workflow have their own page, so the file design is the quicker comparison.
- **Heartbeats:** these only show at namespace level, in the namespace's Usage view and in the `temporal_cloud_v1_billable_action_count` metric, which breaks actions down by type (`record_activity_heartbeat`) and workflow type. Use a namespace with nothing else running, run one design at a time, and compare totals for that time window. Temporal says metric totals can differ from metered usage by up to 5%.

### Cross-checking with temporal-history-action-count

The community tool [temporal-history-action-count](https://github.com/temporal-community/temporal-history-action-count) counts actions from one exported history file. To use it on a run, export the parent and all its child workflows:

```bash
scripts/export-histories.sh recon-<fileId>
```

This writes one JSON file per workflow to `data/histories/recon-<fileId>/`. Then, from a checkout of the tool, run it on each file and add up the results:

```bash
uv run temporal-billable /path/to/batch-sample/data/histories/recon-<fileId>/recon-<fileId>.json
```

Two things to account for when comparing with `count-actions`. The tool counts each child workflow as 2 actions on the parent's side, so summing every child's own history adds 1 more per child; subtract one per chunk. It also doesn't count heartbeats, which `count-actions` estimates. [RESULTS.md](RESULTS.md) has the comparison for the experiment runs.

## Project layout

Packages follow the usual Spring Boot layering, named by what triggers the code: a person at a terminal (`cli`), an outside system (`listener`), or Temporal (`workflow`, `activity`).

```
src/main/java/com/example/recon/
├── ReconApplication.java   @SpringBootApplication entry point
├── cli/                    how a person runs the POC locally: CommandLauncher, CommandLineArgs,
│                           StartOptions, and one runner per command (GenerateCommand,
│                           StartCommand, IntakeCommand, WorkerStatus). Not part of the
│                           production system.
├── listener/               what outside systems trigger. InboxListener stands in for the queue
│                           consumer; VendorCallbackListener stands in for the vendor's callback.
├── design/
│   ├── file/               file design (one workflow per file), task queue reconciliation-file
│   │   ├── workflow/       ReconciliationFileWorkflow + Impl
│   │   └── activity/       ReconciliationActivities + Impl
│   └── chunk/              chunk design, task queue reconciliation-chunk
│       ├── workflow/       ChunkedFileWorkflow + Impl, ChunkWorkflow + Impl
│       └── activity/       ChunkActivities + Impl
├── service/                business logic both designs share: TxnStore (persistence), RowLoader
│                           (count, parse, insert), ReportWriter, FileIntake (starts a workflow
│                           on the right design's queue), FakeVendor + ScheduledAnswer (the fake
│                           vendor), CsvGenerator, RowSink, ActionCounter (count-actions)
├── repository/             Spring Data JPA repositories, one per table
├── entity/                 JPA entities: TxnRecord, PendingCounter, OutboundQueueEntry
├── dto/                    records: workflow and activity inputs and outputs, ParsedRow,
│                           VendorAnswer, FakeVendorSettings, StartSettings
├── view/                   console output: FileResultPrinter, ActionCountPrinter, UsagePrinter,
│                           TemporalUiLinks
└── config/                 Spring configuration and @ConfigurationProperties
src/main/resources/
├── application.yml         shared settings
└── application-worker.yml  turns on the Temporal workers and schema creation
scripts/
├── run-experiments.sh      runs both designs and records action counts
└── export-histories.sh     exports a run's histories (parent + children) as JSON
```

Tests mirror the same packages.
