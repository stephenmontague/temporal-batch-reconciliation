# Results: why this project uses the Basic Workflow

This project used to run a file two ways, side by side, and we measured both on the same data:

- **Basic Workflow:** one workflow handles the whole file, batch by batch.
- **Fan-Out:** one main workflow (the parent) starts a smaller workflow (a child) for every batch, and they all run at the same time.

Both did exactly the same work with the same code. The only differences were how the work was split up, what you could see in the Temporal UI, and how much Temporal Cloud charged. Based on these results we kept the Basic Workflow and removed Fan-Out. This page is the record of why.

## A few words used on this page

| Word | Meaning |
|---|---|
| **Batch** | A slice of the file. Here, 1,000 rows. |
| **Action** | Something Temporal Cloud charges for: starting a workflow, running a task, a check-in, and so on. |
| **Check-in (heartbeat)** | A message a long-running task sends to say "I'm still working". Each one is an action. |
| **Event history** | Temporal's step-by-step record of everything a workflow did. Every workflow has one, and it has a size limit. |
| **Continue-as-new** | A workflow closing itself and starting a fresh copy with a clean record, carrying over where it left off. The way around the size limit. |
| **Versioning** | Changing workflow code safely while workflows are still running on the old code. |

## What we tested

- One file with 100,000 rows, split into 100 batches of 1,000 rows.
- Two copies of the file with the same data, one for each pattern. Only the row IDs differ, so the two runs don't get mixed up in the database.
- Run on Temporal Cloud on November 5, 2026, with the workers and a fake vendor on a laptop.

## What we measured

| | Basic Workflow | Fan-Out |
|---|---|---|
| Workflows | 1 | 101 (1 parent + 100 children) |
| **Actions** | **408** | **1,103** |
| Time to finish | 45 seconds | 49 seconds |
| Result | All rows done | All rows done |

**Fan-Out cost about 2.7 times as much and was not faster.** Both took about as long as the vendor took to answer.

### Where the actions come from

| | Basic Workflow | Fan-Out |
|---|---|---|
| Starting workflows | 1 | 201 (each child costs 2: one for the parent asking, one for the child starting) |
| Running tasks | 205 | 502 |
| Check-ins | 200 | 200 |
| Waiting for the vendor (a timer, then a "done" message) | 2 | 200 |
| **Total** | **408** | **1,103** |

Both patterns run the same tasks for every batch. Fan-Out costs more for three reasons:

1. Every batch is its own workflow, and starting a workflow costs actions.
2. Every batch does its own bookkeeping (counting what's left, checking on progress, cleaning up). The basic workflow does that once for the whole file.
3. Every batch waits for the vendor on its own. The basic workflow waits once for the whole file.

Check-ins are about half of the basic workflow's cost. Each batch sends 2.

**Running a file again isn't free.** We ran both files a second time. There was nothing left to do, but it still cost 406 actions (basic) and 903 (Fan-Out), because the workflows still read the whole file and check every batch.

## Bigger files

The cost grows in a straight line with the number of batches:

- **Basic Workflow:** **4 actions per batch**, plus 8.
- **Fan-Out:** **11 actions per batch**, plus 3.

| File size | Batch size | Batches | Basic Workflow | Fan-Out |
|---|---|---|---|---|
| 100,000 rows (tested) | 1,000 | 100 | **408** | **1,103** |
| 1 million rows | 1,000 | 1,000 | **~4,000** | **~11,000** |
| 4 million rows | 1,000 | 4,000 | **~16,000** | **Too many batches.** One parent can't safely run 4,000 children. See below. |
| 4 million rows | 4,000 | 1,000 | **~4,000** | **~11,000** |

These estimates assume each task finishes in under about 20 seconds, so it only checks in once.

Three things to notice:

- **Batch size matters more than file size.** Four times the rows with four-times-bigger batches costs the same as 1 million rows. Fewer, bigger batches means fewer actions in both patterns.
- **Fan-Out always costs about 2.5 to 3 times as much** for the same number of batches.
- **Fan-Out has a ceiling.** Temporal recommends no more than about 1,000 children per parent, and the Fan-Out version enforced it. Past that, you'd use bigger batches, or a tree of parents (a parent of parents).

## Pros and cons

### Basic Workflow

**Pros**
- **Cheaper:** about a third of the actions of Fan-Out.
- **Simpler:** one workflow per file, so there's one thing to watch, one thing to debug, and one thing to change.
- **Built-in pacing:** it runs a set number of batches at a time, so it can't flood the vendor or the database.

**Cons**
- **Its record keeps growing.** Every batch adds to the one workflow's event history. Fine at 100 batches. Somewhere past 1,000 batches Temporal starts warning about it, and a few thousand more would hit the limit and stop the workflow.
- **Very big files need continue-as-new.** To handle them, the workflow would have to start fresh copies of itself partway through, carrying over where it left off. This project doesn't do that yet.
- **Harder to see one batch.** The UI shows one long list of steps for the whole file. To check one batch, you dig through that list.
- **One problem affects the whole file.** If something goes wrong, the whole file is stuck or retried, not just the bad batch.
- **Code changes need care.** A file can take hours, so if you change the code while one is running, you have to use versioning so the running workflow can still follow its original steps. Its long record also makes that replay slower.

### Fan-Out with Child Workflows

**Pros**
- **Easy to see each batch.** Every batch is its own workflow in the UI, with its own status, so a stuck batch stands out right away.
- **Problems stay small.** A bad batch affects only its own child. You can look at it or reset it without touching the rest.
- **Each record stays small.** A child's event history is about the same size no matter how big the file is, so the children never need continue-as-new.
- **Batches finish on their own.** Each batch moves on as soon as it's ready, without waiting for the rest of the file.

**Cons**
- **Costs about 2.5 to 3 times as much** for the same file.
- **Not faster.** All the batches still share the same workers and the same database. In this test it finished in about the same time as the basic workflow, and each task waited longer for a free worker.
- **No pacing.** Every child starts at once. With a huge file, that can overload the vendor or the database unless you add limits.
- **The parent has a ceiling.** The parent's record grows with every child, and Temporal recommends about 1,000 children at most. Bigger files need bigger batches, continue-as-new on the parent, or a tree of parents.
- **Two workflows to keep in step.** Code changes have to keep the parent and the children compatible. Versioning still matters, because the children run as long as the file does. Each child's small record does make replays quick.
- **More moving parts.** 101 workflows instead of 1, so there's more to watch and more to clean up.

## What we decided

- **We kept the Basic Workflow.** For files of a few hundred batches it's the cheapest and simplest, and Fan-Out wasn't faster.
- **Instead of a child per batch, failed rows get retried on their own.** The most common "one bad part" problem is a handful of rows the vendor rejected for a temporary reason. The basic workflow now sends just those rows again, with Temporal's retry policy and backoff, and leaves the others alone. That costs a few actions instead of a workflow per batch. See [Retrying failed rows](README.md#retrying-failed-rows).
- **Fan-Out would be worth another look** if someone needs to see and fix each batch on its own, and the extra cost is worth it.
- **For very big files, the basic workflow needs more.** Bigger batches, or continue-as-new partway through. Temporal's [Batch Processing Patterns](https://docs.temporal.io/design-patterns/batch-processing-patterns) page describes those options.

## How we measured

- **Actions:** counted from each workflow's history, the way [`count-actions`](README.md#measuring-actions) does. Check-ins aren't in the history, so they're estimated from how long each task ran.
- **Checked against Temporal Cloud:** a later fresh run of both files, with new data, came to exactly the same numbers in Temporal Cloud's own metrics ([METRICS.md](METRICS.md)), check-ins included: 408 and 1,103. The second runs matched too (406 and 903).
- **Time and history size:** from Temporal Cloud's record of each workflow.
- **Limits:** from Temporal's [workflow limits](https://docs.temporal.io/workflow-execution/limits) and [child workflow guidance](https://docs.temporal.io/child-workflows#when-to-use-child-workflows).
