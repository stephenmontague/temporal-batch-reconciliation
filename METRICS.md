# Watching actions, heartbeats and workers with Temporal metrics

`count-actions` estimates a run's actions from its workflow histories. This guide sets up two other views:

- **Temporal Cloud metrics:** what Temporal Cloud itself reports, minute by minute, through its [OpenMetrics endpoint](https://docs.temporal.io/cloud/metrics/openmetrics). It is the only place you can see activity heartbeats, because heartbeats never appear in workflow history. Answers "what did Temporal Cloud do and count?"
- **SDK metrics:** what the worker app reports about itself: activity slots, schedule-to-start latency, activity latency, sticky cache. Answers "is my worker keeping up?" See [SDK metrics from the worker](#sdk-metrics-from-the-worker).

Everything runs locally in Docker: Prometheus collects both and Grafana shows them on two dashboards that are set up for you.

## How the pieces fit

```
 Temporal Cloud                     your laptop (docker compose)
┌──────────────────────┐   pull    ┌──────────────┐  PromQL  ┌──────────────┐
│ metrics.temporal.io  │ ◄──────── │  Prometheus  │ ◄─────── │   Grafana    │
│ /v1/metrics          │  every    │  keeps 30 d  │          │  dashboards  │
│ (last full minute)   │  30 s     │  :9090       │          │  :3000       │
└──────────────────────┘           └──────────────┘          └──────────────┘
          ▲                               │ pull every 10 s
   Bearer API key: a service              ▼ (host.docker.internal)
   account with the Metrics         ┌───────────────────────────────┐
   Read-Only role                   │ your worker (not in Docker)   │
                                    │ :8091 basic-workflow-worker   │
                                    │ /actuator/prometheus          │
                                    └───────────────────────────────┘
```

- **Pull, not push.** Temporal Cloud doesn't send metrics anywhere. It publishes the most recent full minute at one URL, and whatever you point at that URL (Prometheus here; Datadog, Grafana Cloud, an OpenTelemetry Collector at customers) fetches it on a schedule and keeps the history. The endpoint has no history of its own and no query API.
- **Account-wide.** One URL covers every namespace in the account. Each series has a `temporal_namespace` label to tell them apart.
- **A different API key.** The workers use a namespace API key (`TEMPORAL_API_KEY`). Metrics use a key that belongs to a *service account* with the account-level **Metrics Read-Only** role, which can read metrics and do nothing else.
- **Server-side numbers.** These come from Temporal Cloud, not from your workers, so they reflect what Temporal Cloud actually counted. SDK metrics, from inside the worker, are a separate thing; see [SDK metrics from the worker](#sdk-metrics-from-the-worker).

## Set it up

### 1. Create a service account and API key

You need the **Account Owner** or **Global Admin** role. A Namespace Admin can't grant Metrics Read-Only.

1. In the Temporal Cloud UI, go to **Settings → Service Accounts → Create Service Account**.
2. Give it the **Metrics Read-Only** account-level role. It needs no namespace permissions.
3. Open the service account and create an API key. Copy it; it's shown once.

### 2. Check the key with one manual scrape

Put the key in `local.env` at the repo root, next to your namespace settings (see [Running against Temporal Cloud](README.md#running-against-temporal-cloud); `cp local.env.example local.env` if you haven't yet):

```bash
TEMPORAL_CLOUD_METRICS_API_KEY=<the key from step 1>
```

Then:

```bash
scripts/check-cloud-metrics.sh
```

This fetches the endpoint once, the same way Prometheus will, filtered to the billable action metric. It prints three things:

- `X-Completeness`: `complete` is what you want. `limited` means the response hit the 50,000 data point cap and was cut short. Add a namespace filter (see [Limits and gotchas](#limits-and-gotchas)).
- The raw lines, exactly as Prometheus will receive them.
- The same minute converted into action counts by namespace and action type.

If nothing ran in the namespace during the minute published about three minutes ago, there are no lines. Start a run and try again. Don't loop it: each call counts toward the account's 180 requests per hour.

Note the exact `temporal_namespace` value in the output. You'll need it if you filter by namespace.

### 3. Start Prometheus and Grafana

From the repo root:

```bash
docker compose -f observability/docker-compose.yml up -d
```

- Grafana: http://localhost:3000. The **Temporal Cloud: actions and heartbeats** dashboard is the home page; **Temporal workers: SDK metrics** is next to it in the **Temporal** folder. No login; this setup is only reachable from your own machine.
- Prometheus: http://localhost:9090. **Status → Target health** should show `temporal-cloud` as **UP**. If it says `401 Unauthorized`, the key is wrong or lacks the role. If Prometheus won't load at all, `docker compose -f observability/docker-compose.yml logs prometheus` says whether the key was missing from the environment. The two `workers` targets are **UP** while the worker apps run and **DOWN** when they don't; that's expected.

If Prometheus and Grafana were already running from before SDK metrics were added, the same `up -d` picks up the new compose settings; then run `docker compose -f observability/docker-compose.yml restart grafana` so Grafana loads the new dashboard and folder.

Compose reads the metrics key from `local.env` (through the `observability/.env` link, which points at `../local.env`) and passes only that key to the Prometheus container. The container writes it to a file of its own, because Prometheus reads bearer tokens from files. The namespace API key never reaches it, and `local.env` is gitignored.

### 4. Run something and watch

Run a file against Temporal Cloud as in [Running against Temporal Cloud](README.md#running-against-temporal-cloud). Data appears about **3 to 4 minutes** after it happens. That's the endpoint's built-in delay, not a problem with the setup.

To stop it:

```bash
docker compose -f observability/docker-compose.yml down
```

Add `-v` to also delete the stored metrics.

## Reading the dashboard

Pick a **Namespace** and, optionally, **Workflow type** at the top. Every panel follows the time range you choose.

| Panel | What it answers |
|---|---|
| **Billable actions** | How many billable actions in the selected time range |
| **Activity heartbeats** | How many of those were activity heartbeats (`record_activity_heartbeat`, `record_activity_heartbeat_by_id`) |
| **Heartbeats as a share of actions** | Whether heartbeats are a meaningful part of the bill |
| **Throttled actions** | Whether a burst went over the namespace's actions-per-second limit. Anything above zero means it did |
| **Billable actions by action type** | What kind of actions: `schedule_activity`, `start_workflow`, `start_child_workflow`, `signal_workflow`, timers, heartbeats… The legend's **Total** column gives each type's sum for the time range |
| **Billable actions by workflow type** / **Activity heartbeats by workflow type** | Which workflow caused them. Here: `BasicWorkflow` |
| **Actions by workflow type and action type** | One table with a row per workflow type and a column per action type. The panel to compare with `count-actions` |
| **Namespace capacity** (collapsed) | Average actions per second against the namespace's limit |

Every panel has a description (hover the ⓘ) explaining what its query does.

## The one thing to understand: these are rates, not counts

This is what trips people up, and it's the most useful thing to explain to a customer.

The metric names end in `_count`, but **every Temporal Cloud metric is a gauge holding a per-second rate, averaged over one minute**. A data point of `2.0` on `temporal_cloud_v1_billable_action_count` means "2 actions per second during that minute", which is 120 actions. Each series gets **one data point per minute**, and that minute is published about three minutes after it ends.

So to turn the data back into counts, every panel uses the same pattern:

```promql
sum(sum_over_time(temporal_cloud_v1_billable_action_count{temporal_namespace="..."}[$__range])) * 60
```

Read from the inside out:

1. `sum_over_time(...[$__range])` adds up every per-minute data point in the window, for each series.
2. `* 60` converts per-second rates into actions per minute. Because each data point covers one minute, the sum of those is the total.
3. The outer `sum(...)` adds the series together (all action types, all workflow types).

The bar charts use `$__interval` instead of `$__range`, so each bar is the count for that bar's interval. Zooming out doesn't change the total.

### Why not the obvious queries

Prometheus users usually reach for one of these. Neither works on this data:

| Query | What goes wrong |
|---|---|
| `rate(...)` or `increase(...)` | Those are for counters that only go up. These values are already rates and go up and down. |
| `sum(temporal_cloud_v1_billable_action_count)` on a graph | Shows actions per second, not a count. When a series stops (a minute with no heartbeats, say), Prometheus keeps showing its last value for up to 5 minutes, so quiet periods look busy. |
| `avg_over_time(...[24h:1m]) * 86400` (from Temporal's cost-governance guide) | Fine for steady, around-the-clock traffic. On bursty traffic it has the same problem: the `:1m` subquery fills each quiet minute with the last value seen. |

The last row was tested against this dashboard's Prometheus with known synthetic data: heartbeats in every other minute, 900 in total. `sum_over_time(...) * 60` returned the right count. The subquery version returned 1,800. Batch jobs like this one are bursty, so the difference matters here.

### Why the scrape config looks the way it does

[observability/prometheus/prometheus.yml](observability/prometheus/prometheus.yml) has comments on each setting. The important ones:

- **`honor_timestamps: true`.** Each data point carries the minute it describes (three minutes ago). Prometheus keeps that timestamp instead of using the time of the scrape, so data lands on the right minute.
- **`scrape_interval: 30s`.** A new minute is published every 60 s. Scraping every 30 s never misses one. Both scrapes in a minute return the same timestamp and value, and Prometheus stores it once, so nothing is counted twice. That's what keeps `sum_over_time` exact.

## Heartbeats

An activity heartbeat costs one action only if it reaches Temporal Cloud. The SDK throttles them: it sends the first one right away, then at most one per **80% of the heartbeat timeout**, however often the activity code calls `heartbeat()`.

In this repo, `seedBatch` calls `heartbeat()` after every 200 rows. With a 30-second heartbeat timeout, at most one heartbeat reaches the server every 24 seconds, plus the first. So the heartbeat count depends mostly on **how long the activity runs and on the timeout**, not on the 200-row checkpoint. `count-actions` estimates it the same way: `1 + floor(duration / (0.8 × heartbeat timeout))` per activity. The heartbeat panels show whether that estimate holds.

Ways to bring heartbeat actions down: a longer heartbeat timeout (fewer sent, but a lost worker is noticed later), or shorter activities that finish before the second heartbeat would go out.

## Checking `count-actions` against Temporal Cloud

1. Use a namespace where nothing else is running.
2. Run one file at a time and leave a few minutes between runs, so each run is a separate block on the charts.
3. Wait about 5 minutes after the run finishes so the last minutes are published, then set the time range to cover just that run.
4. Compare the **Actions by workflow type and action type** table with what `count-actions` printed for the same run.

Expect small differences. The metric is an estimate that can differ from metered usage by up to 5%, some billable action types aren't in this metric, and `count-actions` estimates heartbeats rather than counting them. For exact numbers, use the Cloud UI's **Usage** page or the Billing API. The metric is for trends, attribution and alerts, not for reconciling the bill.

## Limits and gotchas

| Limit | What happens | What to do |
|---|---|---|
| **180 requests per hour per account** (about 3 a minute) | HTTP 429. The limit is per *account*: every scraper on the account shares it, including a teammate's Grafana Cloud or Datadog integration | This setup uses 120 an hour. On a shared account, check whether someone already scrapes it |
| **50,000 data points per scrape** | The response is cut short and `X-Completeness` is `limited`, so data silently goes missing | Uncomment `params: namespaces:` in `prometheus.yml` and list the namespaces you care about |
| **No activity, no series** | A metric doesn't appear for a minute with nothing to report. The dashboard's queries already treat a missing minute as zero | Add `or vector(0)` to your own queries where a missing value should read as 0 |
| **3-minute delay** | The latest few minutes always look empty | Allow for it in alerts too |
| **Not the bill** | Excludes storage and some features; can differ by up to 5% | Billing API or the Usage page for real numbers |

`temporal_activity_type` is an opt-in label (add `labels: ["temporal_activity_type"]` under `params`). It's available on the activity metrics, such as `temporal_cloud_v1_activity_success_count`, but **not** on `temporal_cloud_v1_billable_action_count`, which only splits by action type and workflow type. You can't get heartbeats per activity type from Temporal Cloud. In this repo only `seedBatch` heartbeats more than once, so the workflow type is enough.

## Explaining it to customers

- **"Where do I see my actions?"** Four places, at different levels of detail: the Billing API and Usage page (exact, for the bill), Event History in the UI (per workflow, but no heartbeats or queries), and this metric (per minute, by action type and workflow type, near real time). The metric is the one for dashboards, alerts and "what changed?".
- **"How do I connect it?"** Create a Metrics Read-Only service account and an API key, then point any Prometheus-compatible scraper at `https://metrics.temporal.io/v1/metrics` with the key as a Bearer token. Datadog, Grafana Cloud, New Relic and Elastic have ready-made integrations, so most customers don't have to run Prometheus themselves.
- **"Why don't my numbers add up?"** Almost always the rate-versus-count issue: they graphed the raw value (per second) or used `rate()`. Multiply by 60 and add up the per-minute points.
- **"What's driving my bill?"** Group by `action_type` and `temporal_workflow_type`. A sudden jump in one workflow type is usually a deploy or a retry loop. A large `record_activity_heartbeat` share usually means short heartbeat timeouts on long activities.
- **"Can I alert on it?"** Yes: on throttling (`temporal_cloud_v1_total_action_throttled_count > 0`), on actions per second approaching `temporal_cloud_v1_action_limit`, or on hourly actions above a baseline. Allow for the 3-minute delay.

## SDK metrics from the worker

Temporal Cloud metrics say what Temporal Cloud counted. SDK metrics come from inside the worker and say how it's coping: are activities waiting for a free slot, how long does each activity take, is the workflow cache holding up. They don't count actions or heartbeats. They work the same against Temporal Cloud or a local dev server.

### How it's wired

- The worker has `spring-boot-starter-actuator` and `micrometer-registry-prometheus`. The Temporal Spring Boot starter sees Micrometer's registry and reports every SDK metric into it, every 10 seconds. No metrics code is needed.
- The worker runs a small web server only to serve those metrics: http://localhost:8091/actuator/prometheus. The port and settings are at the bottom of its `application.yml`; `SERVER_PORT` overrides the port. The worker prints the URL when it starts.
- Every series has an `application` label (`basic-workflow-worker`), and Prometheus adds a `worker` label (`basic-workflow`).
- Prometheus's `workers` job scrapes it every 10 seconds at `host.docker.internal`, which is your machine as seen from the container.
- Timers get histogram buckets (`management.metrics.distribution.percentiles-histogram.temporal`), which is what lets Grafana show p95 latencies.

The server listens on 127.0.0.1 only. Docker Desktop (Mac and Windows) reaches it through `host.docker.internal` anyway. On Linux, Docker reaches the host through its bridge network instead, so start the worker with `SERVER_ADDRESS=0.0.0.0`.

### Reading the SDK dashboard

| Panel | What it answers |
|---|---|
| **Workflows completed** / **Activities executed** | How much work the worker did in the range |
| **Peak activities running** | The most activities running at once. Near the slot limit (20) means the worker is the bottleneck |
| **p95 activity schedule-to-start** | How long activities waited in the task queue before a worker picked them up. The clearest "can't keep up" signal |
| **Activities running vs slot limit** | Running activities over time, against the limit |
| **Activity / workflow task schedule-to-start (p95)** | Waiting time over time, for activities and for workflow tasks |
| **Activity executions per second** / **execution latency (p95)**, by activity type | Which activities run, and how long each attempt takes |
| **Activity failures per second** | Attempts that threw. Each one is retried, and each retry is another billable action. `RetryFailedRows` shows here when resent rows fail again: that's its retry policy at work |
| **Workflows completed per minute**, by workflow type | `BasicWorkflow`: one per file |
| **Sticky cache** | Hits, misses and forced evictions. Expect one eviction per finished workflow; more than that, or any misses, mean the worker replayed history |

On a 50,000-row file at 1,000 rows per batch (local dev server), p95 activity schedule-to-start was 0.05 s and p95 `SeedBatch` execution 0.25 s: with `--max-parallel=10`, the workflow never asks for more than the worker's 20 slots. The earlier fan-out version, which started 50 children at once, measured 0.43 s and 1.37 s on the same data, because every batch hit the worker and the database at the same moment.

### Gotchas

- **The slot gauges usually read 0.** `temporal_worker_task_slots_used` is sampled every 10 s, and most of these activities finish in milliseconds, so a sample rarely catches one running. The dashboard shows busy-ness as `rate(temporal_activity_execution_latency_seconds_sum[30s])` instead: execution seconds per second, which is the average number of activities running. It's a 30-second average, so a shorter burst reads lower than its true peak. The raw gauge is still on the panel as a dotted line.
- **Each activity timer is reported twice.** Once per `activity_type` and once more as a total with `activity_type="none"`. Filter with `activity_type!="none"` before summing, or every count doubles.
- **Counters are `_total`, timers are `_seconds`.** Micrometer renames the SDK's metrics for Prometheus: `temporal_workflow_completed` becomes `temporal_workflow_completed_total`, and `temporal_activity_execution_latency` becomes `temporal_activity_execution_latency_seconds_bucket`, `_count`, `_sum` and `_max`.
- **Forced evictions aren't always a problem.** The worker evicts a workflow from its cache when the workflow finishes, and counts that as a forced eviction. Expect one per file. Only evictions beyond the completions, or cache misses, mean replays.
- **Short runs are spiky.** A batch run takes well under a minute, so the panels use 30-second windows and a 10-second minimum interval rather than the 1-minute minimum the datasource sets for Temporal Cloud data.

Reference: [SDK metrics](https://docs.temporal.io/references/sdk-metrics).

## Files

| File | What it is |
|---|---|
| [observability/docker-compose.yml](observability/docker-compose.yml) | Prometheus and Grafana, ports bound to localhost |
| [observability/prometheus/prometheus.yml](observability/prometheus/prometheus.yml) | The two scrape jobs (Temporal Cloud, the worker), with a comment on each setting |
| [observability/grafana/provisioning/](observability/grafana/provisioning) | Points Grafana at Prometheus and loads the dashboards at startup |
| [observability/grafana/dashboards/temporal-cloud-actions.json](observability/grafana/dashboards/temporal-cloud-actions.json) | The Temporal Cloud dashboard |
| [observability/grafana/dashboards/temporal-workers-sdk.json](observability/grafana/dashboards/temporal-workers-sdk.json) | The SDK metrics dashboard |
| [basic-workflow-worker's application.yml](basic-workflow-worker/src/main/resources/application.yml) | The worker's metrics port and settings |
| [scripts/check-cloud-metrics.sh](scripts/check-cloud-metrics.sh) | One manual scrape, to check the key and see the raw format |
| [observability/.env](observability/.env) | A link to `../local.env`, so Compose finds the metrics key without exporting it |

References: [OpenMetrics setup](https://docs.temporal.io/cloud/metrics/openmetrics), [API reference and limits](https://docs.temporal.io/cloud/metrics/openmetrics/api-reference), [metrics reference](https://docs.temporal.io/cloud/metrics/openmetrics/metrics-reference), [what counts as an action](https://docs.temporal.io/evaluate/cloud/actions), [cost governance](https://docs.temporal.io/best-practices/cost-governance).
