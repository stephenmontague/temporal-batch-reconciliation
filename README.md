# Batch Processing on Temporal (Java, Spring Boot)

A reference for batch processing on Temporal, built as a file reconciliation pipeline: how to checkpoint large files, wait for asynchronous answers, retry only the rows that failed for a retryable reason, and measure what it costs in Temporal Cloud actions.

## The pattern: Basic Workflow with batching

Temporal's [Batch Processing Patterns](https://docs.temporal.io/design-patterns/batch-processing-patterns) page describes several ways to process a large record set. This project uses the simplest one, the [Basic Workflow](https://docs.temporal.io/design-patterns/batch-processing-patterns#basic-workflow-single-tier-fan-out): **one workflow per file runs the activities for every batch.**

**What "batch" means in this repo:** a slice of the file's rows (`--batch-size`, 1,000 by default). Each activity handles a whole batch, so a 100,000-row file is 100 batches and about 200 activities, well within what one workflow can hold. (Unrelated: the `temporal batch` CLI commands, which run one operation across many existing workflows.)

**Why not fan-out with child workflows?** An earlier version also ran Temporal's [Fan-Out with Child Workflows](https://docs.temporal.io/design-patterns/fanout-child-workflows) pattern on the same data, with the same activities. It cost about 2.7 times as many actions (1,103 vs. 408 for 100,000 rows) and wasn't faster. Its benefit, seeing and fixing each batch on its own, wasn't worth that here. **[RESULTS.md](RESULTS.md) has the measurements, estimates for 1 and 4 million rows, and the pros and cons in plain language.**

## The monorepo

One Maven build, one shared library, three runnable jars. Each app does one job:

| Module | Jar | What it runs |
|---|---|---|
| [`batch-processing-core`](batch-processing-core) | library | Only what more than one app uses: the database store, the records apps pass to each other, the settings, and the workflow names that tie the apps together. Also the CSV generator and the fake vendor, which the worker's tests use too |
| [`basic-workflow-worker`](basic-workflow-worker) | `basic-workflow-worker/target/basic-workflow-worker.jar` | The Temporal worker, polling `batch-processing-basic-workflow`: the workflow, the activities and their options, row parsing and loading, the failed-row retry, and the report |
| [`fake-vendor`](fake-vendor) | `fake-vendor/target/fake-vendor.jar` | The fake downstream vendor and its callback listener |
| [`batch-processing-cli`](batch-processing-cli) | `batch-processing-cli/target/batch-processing-cli.jar` | `generate`, `start`, `intake`, `count-actions` (with the action counter). A Temporal client only; never opens the database |

```
batch-processing-parent (pom.xml)
├── batch-processing-core    shared library
├── basic-workflow-worker    ─┐
├── fake-vendor              ─┼─ each depends on batch-processing-core only
└── batch-processing-cli     ─┘
```

The CLI doesn't depend on the worker. It starts a workflow **by type name** on the worker's task queue, and the vendor signals workflows **by signal name**. Those names live in one place, [WorkflowNames](batch-processing-core/src/main/java/com/example/batchprocessing/core/contract/WorkflowNames.java), and the worker pins its workflow interface to them (`@WorkflowMethod(name = WorkflowNames.BASIC_WORKFLOW)`), so a rename on either side is a compile error.

## The production flow, and what stands in for each part

```
 ┌────────────┐     ┌──────────────────────┐     ┌─────────────────────────────────┐     ┌──────────────┐     ┌──────────┐
 │ CSV file   │ ──► │  trigger             │ ──► │ count → seed → counter → submit │ ──► │ outbound     │ ──► │downstream│
 │ arrives    │     │ (batch-processing-cli)│     │ → wait(all-done | deadline)     │     │ queue        │     │ service  │
 └────────────┘     └──────────────────────┘     │ → mark stragglers               │     └──────────────┘     └────┬─────┘
                                                 │ → retry retryable failures      │                               │
                                                 │ → CSV report                    │                               │
                                                 │   (basic-workflow-worker)       │                               │
                                                 └─────────────────────────────────┘ ◄── all-done signal ◄── answer listener
                                                                                         (fake-vendor, when pending counter hits 0)
```

| Production system | Stand-in here |
|---|---|
| A file arriving in storage | A `.csv` file in `data/inbox/` |
| Queue message or event that starts a run | [InboxListener](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/start/InboxListener.java) polls the inbox and starts one workflow per file (`batch-processing-cli intake`) |
| Database rows with insert-if-absent writes | SQLite `data/batch-processing.db` through Hibernate (Spring Data JPA), primary key on transaction ID; rows that already exist are skipped |
| Outbound queue to the downstream service | `outbound_queue` table |
| Downstream service + async answers (minutes later) | `fake-vendor`: answers each row 1–30 s later; 1% of answers fail with `ACCOUNT_LOCKED` or `TIMEOUT` |
| Callback listener | [VendorCallbackListener](batch-processing-core/src/main/java/com/example/batchprocessing/core/vendor/VendorCallbackListener.java), in the `fake-vendor` process: applies answers, decrements the file's pending counter, and signals the workflow by ID at zero |

## The steps

[BasicWorkflowImpl](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/workflow/impl/BasicWorkflowImpl.java) is the whole workflow, written out top to bottom in its `reconcile` method; each step's comment ends with its activity count. It calls the activities in [ReconciliationActivities](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivities.java) with the options in [ReconciliationActivityOptions](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivityOptions.java). [WALKTHROUGH.md](WALKTHROUGH.md) follows one file through them in depth.

1. **countRows**, then split the file into batches.
2. **seedBatch × N**: parse, validate, and persist each batch, at most `--max-parallel` (10) at a time. Rows that already exist are skipped, so a re-parse is safe. Heartbeats a checkpoint (next offset plus counts) after every 200-row insert, so a retry resumes partway through.
3. **startCounter**: create the file's pending counter from the seeded rows, and save the workflow's ID on it so the vendor knows whom to signal. This finishes before anything is sent.
4. **submitBatch × N**: move seeded rows onto the outbound queue (`SEEDED → SUBMITTED`), capped the same way.
5. **Wait** for the `allDone` signal or the deadline. The deadline is measured from when the file's run started, so a worker outage counts against it. Before waiting, **getPending** reads the live counter; if it is already zero (the signal came early or was lost), it skips the wait.
6. **markTimeouts**: any row still waiting on the vendor becomes `TIMED_OUT`.
7. **retryFailedRows**: send rows that failed with a retryable error code (`TIMEOUT` by default) to the vendor again. See [Retrying failed rows](#retrying-failed-rows).
8. **writeReport**: a per-row CSV in `data/reports/<fileId>-report.csv`. Outcome is `COMPLETED` if the deadline sweep found nothing, otherwise `TIMED_OUT`.

Row states: `SEEDED → SUBMITTED → COMPLETED | FAILED | TIMED_OUT`, plus `INVALID` for rows that fail validation (bad amount, currency, or date), and `RETRYING` for a failed row that was sent again and is waiting for its new answer. They're defined in [TxnState](batch-processing-core/src/main/java/com/example/batchprocessing/core/store/TxnState.java).

### Retrying failed rows

The vendor answers asynchronously, so a failed row isn't an exception anywhere: `submitBatch` succeeds, and the failure arrives later as an answer written to the database (`FAILED`, with an error code). Some codes are worth trying again and some aren't. A vendor `TIMEOUT` is usually temporary, but an `ACCOUNT_LOCKED` needs a person.

So after the main pass, the workflow calls one activity, **retryFailedRows**, and lets Temporal's retry policy do the trying again:

1. Each attempt ([FailedRowRetrier](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/processing/FailedRowRetrier.java)) finds the file's `FAILED` rows with a retryable code, moves them to `RETRYING`, and puts them back on the outbound queue.
2. It waits for their answers, checking the database every second and heartbeating while it waits.
3. If none failed again, it succeeds. If some did, it throws a retryable `ROWS_STILL_FAILING` failure, and Temporal runs the next attempt after the backoff: **30 s, then 60 s, then 2 min, 4 attempts in all** (`FAILED_ROW_RETRY` in [ReconciliationActivityOptions](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivityOptions.java)). Each attempt only sends what's still failing.
4. If the last attempt still fails, the workflow **catches** it, sweeps any row still waiting, and finishes the file anyway. Those rows stay `FAILED` in the report.

The workflow passes the activity only references (the file ID and the list of codes), never row IDs, so it works the same for 10 failed rows or 40,000. Which codes are retryable is a setting: `--retry-codes=TIMEOUT,ACCOUNT_LOCKED`, or `--retry-codes=` to retry nothing. The result says how it went: `NOT_NEEDED`, `SUCCEEDED (after N attempts)`, or `GAVE_UP (after 4 attempts)`.

Resent rows don't touch the pending counter, because the retry activity does its own waiting. So the vendor sends no second `allDone` signal.

### Activity options

[ReconciliationActivityOptions](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/activity/ReconciliationActivityOptions.java) follows Temporal's guidance on [activity timeouts](https://docs.temporal.io/encyclopedia/detecting-activity-failures) and uses per-activity-type options: one activity stub with `DEFAULT` options, plus overrides keyed by activity type.

| Options | Activities | Start-To-Close | Heartbeat timeout | Retries |
|---|---|---|---|---|
| `DEFAULT` | `countRows`, `startCounter`, `getPending`, `markTimeouts`, `writeReport` | 60 s | none | `RETRY_POLICY`: 1 s, doubling, up to 1 min apart, no limit |
| `LONG_RUNNING` | `seedBatch`, `submitBatch` | 10 min | 30 s | `RETRY_POLICY` |
| `FAILED_ROW_RETRY` | `retryFailedRows` | 2 min | 30 s | `FAILED_ROW_RETRY_POLICY`: 30 s, doubling, 4 attempts |

Most activities retry without limit, because their failures are faults (a database hiccup, a lost worker) that clear up. A file that doesn't exist fails with a non-retryable error instead. `retryFailedRows` is the exception: failing again is a normal business result there, so its attempts are limited on purpose.

**How the 200-row heartbeat relates to the 30-second timeout.** `seedBatch` heartbeats a checkpoint after every 200-row insert. That only has to happen within 30 s to keep a healthy attempt alive, and it takes milliseconds. The SDK sends at most about one heartbeat per 24 s (80% of the timeout), so the 30 s also sets how often a checkpoint reaches the server, how quickly a lost worker is noticed, and the heartbeat count in [Measuring actions](#measuring-actions). `submitBatch` heartbeats once and then runs one transaction, which is safe only while that transaction stays well under 30 s.

Design rules:
- Only references (file path, file ID, offsets, error codes) go through the workflow. Row data stays in the database.
- Workflows decide; activities do the I/O. No clocks, randomness, or I/O in workflow code.
- Every activity is safe to run twice.

## How each Spring Boot app is put together

Every app's `application.yml` starts with `spring.config.import: classpath:batch-processing-shared.yml`, which pulls in [the settings they all share](batch-processing-core/src/main/resources/batch-processing-shared.yml): the Temporal connection, the SQLite dialect, the `batch-processing.*` paths and task queue name, worker capacity, logging, and the `cloud` profile. Each app then adds only its own settings.

The worker lists exactly what it registers in its `application.yml`:

```yaml
spring.temporal.workers:
  - name: basic-workflow
    task-queue: ${batch-processing.basic-workflow-task-queue}
    workflow-classes:
      - com.example.batchprocessing.basicworkflow.workflow.impl.BasicWorkflowImpl
    activity-beans:
      - reconciliationActivities
```

| App | What Spring creates |
|---|---|
| `basic-workflow-worker` | One Temporal worker with the workflow class above and the `reconciliationActivities` bean from [ReconciliationActivitiesConfig](basic-workflow-worker/src/main/java/com/example/batchprocessing/basicworkflow/config/ReconciliationActivitiesConfig.java). Creates the schema (`ddl-auto: update`). Serves SDK metrics on port 8091 ([METRICS.md](METRICS.md)). |
| `fake-vendor` | The `FakeVendor` bean, started and stopped with the context, plus `VendorCallbackListener` ([FakeVendorConfig](fake-vendor/src/main/java/com/example/batchprocessing/vendor/config/FakeVendorConfig.java)). Uses the `WorkflowClient` only to signal. No workers. |
| `batch-processing-cli` | One runner per command, picked by a Spring profile of the same name ([CommandLauncher](batch-processing-cli/src/main/java/com/example/batchprocessing/cli/launch/CommandLauncher.java)). DataSource and JPA auto-configuration are switched off. No workers. |

Persistence is Spring Data JPA with Hibernate and its SQLite community dialect. [TxnStore](batch-processing-core/src/main/java/com/example/batchprocessing/core/store/TxnStore.java) is a transactional service over three repositories, one per table (`txn`, `pending_counter`, `outbound_queue`). Every query is a derived Spring Data method except the deadline sweep, which is one bulk JPQL `update`. The worker creates the schema when it starts, so start it before the vendor on a fresh database. [PersistenceConfig](batch-processing-core/src/main/java/com/example/batchprocessing/core/config/PersistenceConfig.java) opens SQLite in WAL mode with a busy timeout and `BEGIN IMMEDIATE` transactions, because the worker and vendor processes write to the same file.

The workflow class is not a Spring bean: Temporal makes a new instance per run.

## Run it

You need Java 17+, Maven, and the [Temporal CLI](https://docs.temporal.io/cli).

```bash
temporal server start-dev
```

In a second terminal, build every jar:

```bash
mvn package
```

Then run the worker and the fake vendor, each in its own terminal. Start the worker first on a fresh database, because it creates the tables:

```bash
java -jar basic-workflow-worker/target/basic-workflow-worker.jar
```

```bash
java -jar fake-vendor/target/fake-vendor.jar
```

Generate a 100,000-row file and run it with a 2-minute deadline:

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar generate 100000
```

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar start data/inbox/settlement-100000.csv --deadline-seconds=120 --wait
```

Watch it in the UI at http://localhost:8233. The `Retry:` line of the result says whether any rows were sent again. Then count its actions:

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar count-actions basic-workflow-settlement-100000
```

To mimic a file-arrival trigger instead, run the watcher, then drop or generate files into `data/inbox/`:

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar intake --deadline-seconds=120
```

To start over with an empty database, stop the worker and vendor, then:

```bash
rm -rf data/batch-processing.db data/batch-processing.db-wal data/batch-processing.db-shm data/reports
```

### batch-processing-cli commands

| Command | What it does |
|---|---|
| `generate <rows> [--out=FILE] [--invalid-rate=R] [--seed=N]` | Write a fake settlement CSV, `data/inbox/settlement-<rows>.csv` by default. Default invalid rate 0.001, seed 42; the same seed always gives the same rows. |
| `start <file> [flags] [--wait]` | Start the workflow for one file. |
| `intake [flags]` | Watch `data/inbox/` and start one workflow per new file. |
| `count-actions <workflow-id> [--csv] [--label=TEXT]` | Estimate Temporal Cloud billable actions for a workflow. |

Options for `intake` and `start`: `--batch-size=N` (default 1000), `--max-parallel=N` (default 10), `--deadline-seconds=N` (default 7200), `--retry-codes=A,B` (default `TIMEOUT`; empty retries nothing). Options take the `--name=value` form. Run the jar with no arguments to see this list.

The workflow ID is `basic-workflow-<file name>` (a file already named `basic-workflow-*` keeps its name). Starting a file whose workflow is still running is rejected, the same way a duplicate trigger message should be. Starting a file that already finished runs it again: seeding skips every row, the counter is already zero, and the run closes `COMPLETED` right away. Transaction IDs start with the file name, so to run the same data as new, generate it under a new name (`--out`) or clear the database.

### Settings

Shared defaults live in [batch-processing-shared.yml](batch-processing-core/src/main/resources/batch-processing-shared.yml); app-specific ones in each app's `application.yml`. Override any of them with an environment variable or a `--name=value` argument, for example `BATCH_PROCESSING_VENDOR_FAILURE_RATE=0` or `--batch-processing.db=/tmp/batch-processing.db`.

| Property | Environment variable | Default | Used by |
|---|---|---|---|
| `batch-processing.db` | `BATCH_PROCESSING_DB` (also read from `local.env`) | `data/batch-processing.db` | worker, vendor |
| `batch-processing.report-dir` | `BATCH_PROCESSING_REPORT_DIR` | `data/reports` | worker |
| `batch-processing.inbox` | `BATCH_PROCESSING_INBOX` | `data/inbox` | cli |
| `batch-processing.basic-workflow-task-queue` | `BATCH_PROCESSING_BASIC_WORKFLOW_TASK_QUEUE` | `batch-processing-basic-workflow` | worker, cli |
| `batch-processing.worker.max-concurrent-activities` | `BATCH_PROCESSING_WORKER_MAX_CONCURRENT_ACTIVITIES` | `20` | worker |
| `batch-processing.start.batch-size` / `max-parallel` / `deadline-seconds` | `BATCH_PROCESSING_START_BATCH_SIZE` etc. | `1000` / `10` / `7200` | cli |
| `batch-processing.start.retry-codes` | `BATCH_PROCESSING_START_RETRY_CODES` | `TIMEOUT` | cli |
| `batch-processing.vendor.min-delay-seconds` / `max-delay-seconds` | `BATCH_PROCESSING_VENDOR_MIN_DELAY_SECONDS` etc. | `1` / `30` | vendor |
| `batch-processing.vendor.failure-rate` | `BATCH_PROCESSING_VENDOR_FAILURE_RATE` | `0.01` | vendor |
| `batch-processing.vendor.error-codes` | `BATCH_PROCESSING_VENDOR_ERROR_CODES` | `ACCOUNT_LOCKED,TIMEOUT` | vendor |
| `batch-processing.vendor.poll-millis` / `poll-batch` | `BATCH_PROCESSING_VENDOR_POLL_MILLIS` etc. | `500` / `2000` | vendor |

The Temporal connection is the starter's `spring.temporal.*` settings. `batch-processing-shared.yml` reads `TEMPORAL_ADDRESS` (default `localhost:7233`) and `TEMPORAL_NAMESPACE` (default `default`), from the environment or from `local.env` in the directory you run from. For Temporal Cloud, turn on the `cloud` profile instead; see [Running against Temporal Cloud](#running-against-temporal-cloud).

## Tests

```bash
mvn test
```

Each module tests itself:

| Module | Test | What it checks |
|---|---|---|
| batch-processing-core | `seedingTwiceWritesEachRowOnce` | Running `seedBatch` twice writes nothing the second time. |
| | `retryResumesFromHeartbeatedOffset` | A retry with heartbeat details at offset 600 only writes rows 600–999. |
| | `retrySendsOnlyRowsWithARetryableCode` | 10 `TIMEOUT` and 5 `ACCOUNT_LOCKED` failures; with a real fake vendor running, `retryFailedRows` resends only the 10, and they complete. |
| | `retryThrowsWhenRowsFailAgain` | When the vendor fails the resent rows again, the attempt throws `ROWS_STILL_FAILING`. |
| | `retryWithNothingToSendReturnsAtOnce` | No row has a retryable code: nothing is sent and the activity returns straight away. |
| basic-workflow-worker | `completesWhenVendorAnswersEveryRow` | 2,500 rows in 5 batches with real activities, SQLite, and the fake vendor. Ends `COMPLETED`; replays the history; checks the exact activity breakdown (seed and submit 5 each, every other activity once). |
| | `deadlineMarksStragglersTimedOut` | No vendor running. With time skipping, the deadline fires and every row is `TIMED_OUT`. |
| | `retriesFailedRowsWithBackoffUntilTheyGoThrough` | A retry activity that fails twice: Temporal retries it with backoff (skipped in test time) and the result is `SUCCEEDED` after 3 attempts. |
| | `givesUpAfterTheLastAttemptAndStillWritesTheReport` | A retry activity that never succeeds: 4 attempts, then the workflow catches the failure, sweeps, writes the report, and reports `GAVE_UP`. |
| | `rerunOfFinishedFileClosesImmediatelyViaCounterGuard` | A second run of a finished file closes `COMPLETED` in seconds, with no signal, instead of waiting for the deadline. |
| | `BasicWorkflowWorkerApplicationTest` | Boots the real worker app on the starter's embedded test server, with the fake vendor in the same context, starts a file by workflow type name (as the CLI does), and runs 2,000 rows to `COMPLETED`. Fails if `application.yml` didn't register the workflow or the activities. |
| fake-vendor | `FakeVendorApplicationTest` | The vendor app wires up and binds its `batch-processing.vendor` settings. |
| batch-processing-cli | `CommandLineArgsTest`, `StartOptionsTest` | Argument parsing, option defaults, `--retry-codes`, workflow IDs from file names. |

The workflow tests use `TestWorkflowEnvironment` directly, without the full Spring app, so they can skip time. They get `TxnStore` from a small store-only Spring context ([TestStores](batch-processing-core/src/test/java/com/example/batchprocessing/core/testsupport/TestStores.java)) with its own temp database. `batch-processing-core` publishes these helpers as a test-jar that the other modules use.

## Measuring actions

`count-actions` reads a workflow's history (and any child workflows') and counts what Temporal Cloud bills for, using the [Temporal Cloud Actions](https://docs.temporal.io/evaluate/cloud/actions) page:

| History event | Actions |
|---|---|
| Workflow started (client, child, continue-as-new) | 1 |
| Child workflow start initiated (the parent's side) | 1, so a child costs 2 in total |
| Activity scheduled | 1, plus 1 per retry (from the started event's attempt number) |
| Timer started | 1 |
| Signal received, external signal sent | 1 each |
| Search attribute upsert, update accepted | 1 each |
| Local activity markers | 1 per workflow task |

Heartbeats aren't in history. The command estimates them for every activity that has a heartbeat timeout as `1 + floor(duration / (0.8 × heartbeat timeout))`, because the SDK sends the first heartbeat straight away and then throttles to 80% of the timeout. Queries aren't counted.

A file costs about **8 + 4 per batch**: per batch, `seedBatch` and `submitBatch` with one heartbeat each; fixed, the workflow start, five one-off activities, one timer and one signal. The failed-row retry adds 1 action when there's nothing to retry, and a few per attempt when there is (the attempt, its heartbeats). [RESULTS.md](RESULTS.md) has measured numbers.

The local dev server doesn't show billable actions, so these are estimates. To check them against Temporal Cloud, see [Running against Temporal Cloud](#running-against-temporal-cloud) and [METRICS.md](METRICS.md).

To run the experiments, start the worker and the vendor against one database, then run the script (defaults: 100,000 rows at batch sizes 1,000 and 5,000, three runs each):

```bash
BATCH_PROCESSING_DB=data/experiments/batch-processing.db java -jar basic-workflow-worker/target/basic-workflow-worker.jar
```

```bash
BATCH_PROCESSING_DB=data/experiments/batch-processing.db java -jar fake-vendor/target/fake-vendor.jar
```

```bash
scripts/run-experiments.sh
```

It writes every run to `data/experiments/results.csv` and the medians to `data/experiments/summary.csv`. `ROWS`, `RUNS`, `BATCH_SIZES` (for example `"1000 10000"`), `DEADLINE_SECONDS` and `CLI_JAR` override the defaults.

### Running against Temporal Cloud

The `cloud` profile (at the bottom of [batch-processing-shared.yml](batch-processing-core/src/main/resources/batch-processing-shared.yml)) connects with an API key, using the same environment variables as the Temporal CLI. Put them in `local.env` once, at the repo root:

```bash
cp local.env.example local.env
```

Then fill in your namespace address, namespace ID and API key ([local.env.example](local.env.example) explains each line). `local.env` is gitignored. Every app reads it when you run it from the repo root, as do Docker Compose and the scripts, so any terminal works and there's nothing to export. It turns on the `cloud` profile and a separate database (`data/cloud.db`). To go back to the local dev server, move the file aside (`mv local.env local.env.off`) and move it back when you're done. Anything you do export in a shell wins over the file, for that shell only.

Then run the usual apps. The worker and vendor still run on your machine; only Temporal is in the cloud. Start the worker first, so it creates the database tables:

```bash
java -jar basic-workflow-worker/target/basic-workflow-worker.jar
```

```bash
java -jar fake-vendor/target/fake-vendor.jar
```

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar generate 100000
```

```bash
java -jar batch-processing-cli/target/batch-processing-cli.jar start data/inbox/settlement-100000.csv --deadline-seconds=600 --wait
```

`start` prints a link to the workflow in the Cloud UI. `scripts/run-experiments.sh` and `scripts/export-histories.sh` work the same way: the first runs the CLI jar, and the second loads `local.env` for the `temporal` CLI, which reads the same variables. Keep keys in `local.env` (or your shell), never in a tracked file.

To check the action count against what Temporal Cloud records:

- **Everything except heartbeats:** open the workflow in the Cloud UI. Its event history shows a Billable Actions column and a total at the top, which leaves out heartbeats. Compare it with `count-actions` minus the heartbeat estimate.
- **Heartbeats:** these only show at namespace level, in the namespace's Usage view and in the `temporal_cloud_v1_billable_action_count` metric, which breaks actions down by type (`record_activity_heartbeat`) and workflow type. [METRICS.md](METRICS.md) sets up Prometheus and Grafana to collect it. Temporal says metric totals can differ from metered usage by up to 5%.

### Cross-checking with temporal-history-action-count

The community tool [temporal-history-action-count](https://github.com/temporal-community/temporal-history-action-count) counts actions from one exported history file. To use it on a run, export its history:

```bash
scripts/export-histories.sh basic-workflow-settlement-100000
```

This writes one JSON file per workflow to `data/histories/<workflow-id>/`. Then, from a checkout of the tool, run it on the file:

```bash
uv run temporal-billable /path/to/batch-sample/data/histories/basic-workflow-settlement-100000/basic-workflow-settlement-100000.json
```

It doesn't count heartbeats, which `count-actions` estimates.

## Project layout

```
pom.xml                          parent POM: module list, shared dependency versions
RESULTS.md                       measured actions, estimates for bigger files, and why this uses the basic workflow
WALKTHROUGH.md                   one file followed through every step
METRICS.md                       Prometheus + Grafana: Temporal Cloud metrics (actions, heartbeats) and SDK metrics (the worker)
local.env.example                template for local.env (gitignored): Cloud address, API keys, profile
batch-processing-core/           shared library (com.example.batchprocessing.core)
├── contract/                    WorkflowNames: the workflow type and the signal/query names other apps use
├── config/                      BatchProcessingProperties, PersistenceConfig
├── dto/                         records shared across apps: FileInput, FileResult, RetryOutcome, ParsedRow, VendorAnswer, ...
├── store/                       TxnStore (all database access), TxnState, RowSink
│   ├── entity/                  JPA entities: TxnRecord, PendingCounter, OutboundQueueEntry
│   └── repository/              Spring Data JPA repositories, one per table
├── csv/                         CsvGenerator (fake files)
├── vendor/                      FakeVendor, VendorCallbackListener, FakeVendorSettings, ScheduledAnswer
├── resources/batch-processing-shared.yml   settings every app imports, including the cloud profile
└── src/test/.../testsupport/    TestStores, TestVendors, ActivityTypeCounts, InProcessVendorConfig, ... (test-jar)
basic-workflow-worker/           com.example.batchprocessing.basicworkflow
├── workflow/                    BasicWorkflow (interface)
│   └── impl/                    BasicWorkflowImpl: the whole workflow, top to bottom in reconcile()
├── activity/                    ReconciliationActivities (interface), ReconciliationActivityOptions (timeouts, retry policies)
│   └── impl/                    ReconciliationActivitiesImpl
├── processing/                  RowLoader (count, parse, insert, heartbeat), CsvRowParser (validation), FailedRowRetrier, ReportWriter
├── dto/                         records only the worker uses: BatchRequest, SeedResult, SeedProgress, RetryResult
├── config/                      ReconciliationActivitiesConfig: the activity bean
├── src/test/                    BasicWorkflowTest, ReconciliationActivitiesTest, BasicWorkflowWorkerApplicationTest
└── resources/application.yml    the worker: task queue, workflow class, activity bean, metrics port
fake-vendor/                     com.example.batchprocessing.vendor: the app, FakeVendorConfig, VendorProperties
batch-processing-cli/            com.example.batchprocessing.cli
├── launch/                      Command, CommandLauncher, CommandLineArgs
├── command/                     GenerateCommand, StartCommand, IntakeCommand, CountActionsCommand
├── actions/                     ActionCounter, ActionTally, ActionCount (count-actions)
├── start/                       StartSettings, StartOptions, FileIntake, InboxListener
├── config/                      StartProperties, IntakeConfig
└── view/                        console output: FileResultPrinter, ActionCountPrinter, UsagePrinter, TemporalUiLinks
scripts/
├── run-experiments.sh           runs files at several batch sizes and records action counts
├── export-histories.sh          exports a run's histories as JSON
├── check-cloud-metrics.sh       scrapes the Temporal Cloud metrics endpoint once, to check the API key
└── load-local-env.sh            sourced by the other scripts: exports local.env for the temporal CLI and curl
observability/                   docker compose: Prometheus + Grafana for Temporal Cloud and SDK metrics (see METRICS.md)
```
