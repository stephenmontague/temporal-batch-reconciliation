package com.example.batchprocessing.core.store;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.batchprocessing.core.dto.CounterUpdate;
import com.example.batchprocessing.core.dto.OutboundMessage;
import com.example.batchprocessing.core.dto.ParsedRow;
import com.example.batchprocessing.core.dto.VendorAnswer;
import com.example.batchprocessing.core.store.entity.OutboundQueueEntry;
import com.example.batchprocessing.core.store.entity.PendingCounter;
import com.example.batchprocessing.core.store.entity.TxnRecord;
import com.example.batchprocessing.core.store.repository.OutboundQueueRepository;
import com.example.batchprocessing.core.store.repository.PendingCounterRepository;
import com.example.batchprocessing.core.store.repository.TxnRepository;

import jakarta.persistence.EntityManager;

// Every database read and write, for the workflow's activities and the vendor. Every write is safe to repeat,
// because activities that call it can run more than once.
@Component
public class TxnStore {

    private final TxnRepository txns;
    private final PendingCounterRepository counters;
    private final OutboundQueueRepository outbound;
    private final EntityManager entityManager;

    public TxnStore(TxnRepository txns, PendingCounterRepository counters, OutboundQueueRepository outbound, EntityManager entityManager) {
        this.txns = txns;
        this.counters = counters;
        this.outbound = outbound;
        this.entityManager = entityManager;
    }

    // ---- Seeding --------------------------------------------------------------------------------

    // Inserts the rows that aren't in the table yet and returns how many it inserted.
    @Transactional
    public int insertRowsIfAbsent(String fileId, String counterKey, List<ParsedRow> rows) {
        List<String> txnIds = rows.stream().map(ParsedRow::txnId).toList();
        Set<String> knownTxnIds = existingTxnIds(txnIds);
        int inserted = 0;

        for (ParsedRow row : rows) {
            boolean isNewRow = knownTxnIds.add(row.txnId());

            if (isNewRow) {
                TxnRecord record = newRecord(fileId, counterKey, row);
                entityManager.persist(record);
                inserted++;
            }
        }

        return inserted;
    }

    // ---- Pending counter ------------------------------------------------------------------------

    // Creates the counter from the SEEDED rows the first time; afterwards only refreshes which workflow to signal.
    @Transactional
    public int initCounter(String counterKey, String workflowId) {
        Optional<PendingCounter> existing = counters.findById(counterKey);
        PendingCounter counter = existing.orElseGet(() -> createCounter(counterKey, workflowId));
        counter.setWorkflowId(workflowId);

        return counter.getPending();
    }

    @Transactional(readOnly = true)
    public int getPending(String counterKey) {
        Optional<PendingCounter> counter = counters.findById(counterKey);

        return counter.map(PendingCounter::getPending).orElse(0);
    }

    // ---- Outbound queue -------------------------------------------------------------------------

    // Queues the SEEDED rows in [fromLine, toLine) for the vendor and moves them to SUBMITTED.
    @Transactional
    public int enqueueOutbound(String fileId, int fromLine, int toLine) {
        int lastLine = toLine - 1;
        List<TxnRecord> rows = txns.findByFileIdAndStateAndLineNoBetween(fileId, TxnState.SEEDED, fromLine, lastLine);
        Set<String> queuedTxnIds = alreadyQueuedTxnIds(rows);

        for (TxnRecord row : rows) {
            boolean notQueuedYet = queuedTxnIds.add(row.getTxnId());

            if (notQueuedYet) {
                OutboundQueueEntry entry = new OutboundQueueEntry(row.getTxnId(), fileId);
                entityManager.persist(entry);
            }

            row.moveTo(TxnState.SUBMITTED, null);
        }

        return rows.size();
    }

    // The vendor side: takes up to `limit` queued rows and marks them sent.
    @Transactional
    public List<OutboundMessage> takeOutbound(int limit) {
        List<OutboundQueueEntry> queued = outbound.findByStatus(OutboundQueueEntry.QUEUED, Limit.of(limit));
        List<OutboundMessage> messages = new ArrayList<>();

        for (OutboundQueueEntry entry : queued) {
            entry.markSent();
            messages.add(new OutboundMessage(entry.getTxnId(), entry.getFileId()));
        }

        return messages;
    }

    // ---- Vendor answers -------------------------------------------------------------------------

    // Records each answer on its row, then decrements every affected counter once.
    // Answers for rows that aren't waiting for one (duplicates, late answers after the sweep) are ignored.
    // Only first answers count down the counter: a RETRYING row is waited on by the retry activity, not the counter.
    @Transactional
    public List<CounterUpdate> applyAnswers(List<VendorAnswer> answers) {
        List<String> txnIds = answers.stream().map(VendorAnswer::txnId).toList();
        Map<String, TxnRecord> rowsById = rowsById(txnIds);
        Map<String, Integer> answeredPerCounter = new LinkedHashMap<>();

        for (VendorAnswer answer : answers) {
            TxnRecord row = rowsById.get(answer.txnId());
            boolean awaitingAnswer = row != null && TxnState.AWAITING_ANSWER.contains(row.getState());

            if (!awaitingAnswer) {
                continue;
            }

            boolean firstAnswer = TxnState.SUBMITTED.equals(row.getState());

            String answeredState = answer.errorCode() == null ? TxnState.COMPLETED : TxnState.FAILED;
            row.moveTo(answeredState, answer.errorCode());

            if (firstAnswer) {
                answeredPerCounter.merge(row.getCounterKey(), 1, Integer::sum);
            }
        }

        return decrementCounters(answeredPerCounter);
    }

    // ---- Retrying failed rows -------------------------------------------------------------------

    // Sends rows to the vendor again: FAILED rows with one of the retryable error codes, plus RETRYING rows still unanswered
    // from an earlier attempt. Moves them to RETRYING (keeping the old error code until the new answer) and queues them.
    // Returns how many rows it sent.
    @Transactional
    public int resendRetryableFailures(String fileId, Collection<String> retryableErrorCodes) {
        List<TxnRecord> rows = new ArrayList<>();
        rows.addAll(txns.findByFileIdAndStateAndErrorCodeIn(fileId, TxnState.FAILED, retryableErrorCodes));
        rows.addAll(txns.findByFileIdAndState(fileId, TxnState.RETRYING));

        Map<String, OutboundQueueEntry> queueEntries = queueEntriesOf(rows);

        for (TxnRecord row : rows) {
            row.moveTo(TxnState.RETRYING, row.getErrorCode());

            requeue(row, fileId, queueEntries);
        }

        return rows.size();
    }

    @Transactional(readOnly = true)
    public int countAwaitingRetry(String fileId) {
        return txns.countByFileIdAndState(fileId, TxnState.RETRYING);
    }

    @Transactional(readOnly = true)
    public int countRetryableFailures(String fileId, Collection<String> retryableErrorCodes) {
        return txns.countByFileIdAndStateAndErrorCodeIn(fileId, TxnState.FAILED, retryableErrorCodes);
    }

    // ---- Deadline sweep -------------------------------------------------------------------------

    @Transactional
    public int markTimeouts(String counterKey) {
        return txns.moveAll(counterKey, TxnState.UNANSWERED, TxnState.TIMED_OUT);
    }

    // ---- Counts and report ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Integer> countByStateForFile(String fileId) {
        return countEachState(state -> txns.countByFileIdAndState(fileId, state));
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countByStateForCounter(String counterKey) {
        return countEachState(state -> txns.countByCounterKeyAndState(counterKey, state));
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countFailuresForFile(String fileId) {
        List<TxnRecord> rowsWithErrors = txns.findByFileIdAndStateIn(fileId, TxnState.WITH_ERROR_CODE);

        return countByErrorCode(rowsWithErrors);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countFailuresForCounter(String counterKey) {
        List<TxnRecord> rowsWithErrors = txns.findByCounterKeyAndStateIn(counterKey, TxnState.WITH_ERROR_CODE);

        return countByErrorCode(rowsWithErrors);
    }

    // Streams every row of the file in line order, detaching each one so a large file doesn't fill the persistence context.
    @Transactional(readOnly = true)
    public void forEachRow(String fileId, RowSink sink) throws IOException {
        try (Stream<TxnRecord> rows = txns.streamByFileIdOrderByLineNo(fileId)) {
            Iterator<TxnRecord> iterator = rows.iterator();

            while (iterator.hasNext()) {
                TxnRecord row = iterator.next();
                sink.accept(row.getTxnId(), row.getLineNo(), row.getState(), row.getErrorCode());
                entityManager.detach(row);
            }
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------

    private Set<String> existingTxnIds(List<String> txnIds) {
        List<TxnRecord> found = txns.findAllById(txnIds);
        Set<String> existing = new HashSet<>();

        for (TxnRecord row : found) {
            existing.add(row.getTxnId());
        }

        return existing;
    }

    private Set<String> alreadyQueuedTxnIds(List<TxnRecord> rows) {
        List<String> txnIds = rows.stream().map(TxnRecord::getTxnId).toList();
        List<OutboundQueueEntry> found = outbound.findAllById(txnIds);
        Set<String> queued = new HashSet<>();

        for (OutboundQueueEntry entry : found) {
            queued.add(entry.getTxnId());
        }

        return queued;
    }

    private Map<String, OutboundQueueEntry> queueEntriesOf(List<TxnRecord> rows) {
        List<String> txnIds = rows.stream().map(TxnRecord::getTxnId).toList();
        List<OutboundQueueEntry> found = outbound.findAllById(txnIds);
        Map<String, OutboundQueueEntry> byTxnId = new HashMap<>();

        for (OutboundQueueEntry entry : found) {
            byTxnId.put(entry.getTxnId(), entry);
        }

        return byTxnId;
    }

    // The row was sent before, so it normally has a queue entry already; put it back on the queue
    private void requeue(TxnRecord row, String fileId, Map<String, OutboundQueueEntry> queueEntries) {
        OutboundQueueEntry entry = queueEntries.get(row.getTxnId());

        if (entry == null) {
            entityManager.persist(new OutboundQueueEntry(row.getTxnId(), fileId));

            return;
        }

        entry.requeue();
    }

    private Map<String, TxnRecord> rowsById(List<String> txnIds) {
        List<TxnRecord> found = txns.findAllById(txnIds);
        Map<String, TxnRecord> byId = new LinkedHashMap<>();

        for (TxnRecord row : found) {
            byId.put(row.getTxnId(), row);
        }

        return byId;
    }

    private PendingCounter createCounter(String counterKey, String workflowId) {
        int seededRows = txns.countByCounterKeyAndState(counterKey, TxnState.SEEDED);
        PendingCounter counter = new PendingCounter(counterKey, workflowId, seededRows);

        return counters.save(counter);
    }

    private List<CounterUpdate> decrementCounters(Map<String, Integer> answeredPerCounter) {
        List<CounterUpdate> updates = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : answeredPerCounter.entrySet()) {
            String counterKey = entry.getKey();
            int answered = entry.getValue();
            Optional<PendingCounter> found = counters.findById(counterKey);

            if (found.isPresent()) {
                PendingCounter counter = found.get();
                counter.decrement(answered);
                updates.add(new CounterUpdate(counterKey, counter.getWorkflowId(), counter.getPending()));
            }
        }

        return updates;
    }

    private static TxnRecord newRecord(String fileId, String counterKey, ParsedRow row) {
        String state = row.isInvalid() ? TxnState.INVALID : TxnState.SEEDED;

        return new TxnRecord(
                row.txnId(),
                fileId,
                counterKey,
                row.lineNo(),
                row.accountId(),
                row.amountCents(),
                row.currency(),
                row.txnDate(),
                row.merchant(),
                state,
                row.errorCode());
    }

    // Only states that have rows, in TxnState.ALL order
    private static Map<String, Integer> countEachState(ToIntFunction<String> countInState) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (String state : TxnState.ALL) {
            int count = countInState.applyAsInt(state);

            if (count > 0) {
                counts.put(state, count);
            }
        }

        return counts;
    }

    private static Map<String, Integer> countByErrorCode(List<TxnRecord> rows) {
        Map<String, Integer> counts = new TreeMap<>();

        for (TxnRecord row : rows) {
            counts.merge(row.getErrorCode(), 1, Integer::sum);
        }

        return counts;
    }
}
