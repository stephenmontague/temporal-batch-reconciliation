package com.example.recon.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.recon.dto.CounterUpdate;
import com.example.recon.dto.OutboundMessage;
import com.example.recon.dto.ParsedRow;
import com.example.recon.dto.VendorAnswer;
import com.example.recon.entity.OutboundQueueEntry;
import com.example.recon.entity.PendingCounter;
import com.example.recon.entity.TxnRecord;
import com.example.recon.repository.OutboundQueueRepository;
import com.example.recon.repository.PendingCounterRepository;
import com.example.recon.repository.TxnRepository;

import jakarta.persistence.EntityManager;

@Component
public class TxnStore {

    public static final String SEEDED = "SEEDED";
    public static final String INVALID = "INVALID";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String TIMED_OUT = "TIMED_OUT";

    private static final List<String> ALL_STATES = List.of(COMPLETED, FAILED, INVALID, SEEDED, SUBMITTED, TIMED_OUT);

    private final TxnRepository txns;
    private final PendingCounterRepository counters;
    private final OutboundQueueRepository outbound;
    private final EntityManager entityManager;

    public TxnStore(TxnRepository txns, PendingCounterRepository counters,
            OutboundQueueRepository outbound, EntityManager entityManager) {
        this.txns = txns;
        this.counters = counters;
        this.outbound = outbound;
        this.entityManager = entityManager;
    }

    @Transactional
    public int insertRowsIfAbsent(String fileId, String counterKey, List<ParsedRow> rows) {
        Set<String> existing = new HashSet<>();
        txns.findAllById(rows.stream().map(ParsedRow::txnId).toList())
                .forEach(t -> existing.add(t.getTxnId()));
        int inserted = 0;
        for (ParsedRow r : rows) {
            if (existing.add(r.txnId())) {
                entityManager.persist(new TxnRecord(r.txnId(), fileId, counterKey, r.lineNo(), r.accountId(),
                        r.amountCents(), r.currency(), r.txnDate(), r.merchant(),
                        r.errorCode() == null ? SEEDED : INVALID, r.errorCode()));
                inserted++;
            }
        }
        return inserted;
    }

    @Transactional
    public int initCounter(String counterKey, String workflowId) {
        PendingCounter counter = counters.findById(counterKey).orElseGet(() -> counters.save(
                new PendingCounter(counterKey, workflowId,
                        txns.countByCounterKeyAndState(counterKey, SEEDED))));
        counter.setWorkflowId(workflowId);
        return counter.getPending();
    }

    @Transactional(readOnly = true)
    public int getPending(String counterKey) {
        return counters.findById(counterKey).map(PendingCounter::getPending).orElse(0);
    }

    @Transactional
    public int enqueueOutbound(String fileId, int fromLine, int toLine) {
        List<TxnRecord> rows = txns.findByFileIdAndStateAndLineNoBetween(fileId, SEEDED, fromLine, toLine - 1);
        Set<String> queued = new HashSet<>();
        outbound.findAllById(rows.stream().map(TxnRecord::getTxnId).toList())
                .forEach(e -> queued.add(e.getTxnId()));
        for (TxnRecord row : rows) {
            if (queued.add(row.getTxnId())) {
                entityManager.persist(new OutboundQueueEntry(row.getTxnId(), fileId));
            }
            row.moveTo(SUBMITTED, null);
        }
        return rows.size();
    }

    @Transactional
    public List<OutboundMessage> takeOutbound(int limit) {
        List<OutboundMessage> out = new ArrayList<>();
        for (OutboundQueueEntry e : outbound.findByStatus(OutboundQueueEntry.QUEUED, Limit.of(limit))) {
            e.markSent();
            out.add(new OutboundMessage(e.getTxnId(), e.getFileId()));
        }
        return out;
    }

    @Transactional
    public List<CounterUpdate> applyAnswers(List<VendorAnswer> answers) {
        Map<String, TxnRecord> rows = new LinkedHashMap<>();
        txns.findAllById(answers.stream().map(VendorAnswer::txnId).toList())
                .forEach(t -> rows.put(t.getTxnId(), t));
        Map<String, Integer> decrements = new LinkedHashMap<>();
        for (VendorAnswer a : answers) {
            TxnRecord row = rows.get(a.txnId());
            if (row != null && SUBMITTED.equals(row.getState())) {
                row.moveTo(a.errorCode() == null ? COMPLETED : FAILED, a.errorCode());
                decrements.merge(row.getCounterKey(), 1, Integer::sum);
            }
        }
        List<CounterUpdate> updates = new ArrayList<>();
        decrements.forEach((key, n) -> counters.findById(key).ifPresent(c -> {
            c.decrement(n);
            updates.add(new CounterUpdate(key, c.getWorkflowId(), c.getPending()));
        }));
        return updates;
    }

    @Transactional
    public int markTimeouts(String counterKey) {
        return txns.moveAll(counterKey, List.of(SEEDED, SUBMITTED), TIMED_OUT);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countByStateForCounter(String counterKey) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String state : ALL_STATES) {
            int n = txns.countByCounterKeyAndState(counterKey, state);
            if (n > 0) {
                out.put(state, n);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countFailuresForCounter(String counterKey) {
        Map<String, Integer> out = new TreeMap<>();
        for (TxnRecord t : txns.findByCounterKeyAndStateIn(counterKey, List.of(FAILED, INVALID))) {
            out.merge(t.getErrorCode(), 1, Integer::sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countByState(String fileId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String state : ALL_STATES) {
            int n = txns.countByFileIdAndState(fileId, state);
            if (n > 0) {
                out.put(state, n);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> countFailuresByErrorCode(String fileId) {
        Map<String, Integer> out = new TreeMap<>();
        for (TxnRecord t : txns.findByFileIdAndStateIn(fileId, List.of(FAILED, INVALID))) {
            out.merge(t.getErrorCode(), 1, Integer::sum);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public void forEachRow(String fileId, RowSink sink) throws IOException {
        try (Stream<TxnRecord> rows = txns.streamByFileIdOrderByLineNo(fileId)) {
            for (TxnRecord t : (Iterable<TxnRecord>) rows::iterator) {
                sink.accept(t.getTxnId(), t.getLineNo(), t.getState(), t.getErrorCode());
                entityManager.detach(t);
            }
        }
    }
}
