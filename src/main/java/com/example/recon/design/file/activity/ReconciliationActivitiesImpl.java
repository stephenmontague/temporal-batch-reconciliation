package com.example.recon.design.file.activity;

import java.nio.file.Path;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ReportSummary;
import com.example.recon.dto.SeedResult;
import com.example.recon.service.ReportWriter;
import com.example.recon.service.RowLoader;
import com.example.recon.service.TxnStore;

import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;

@ActivityImpl(taskQueues = "${recon.file-task-queue}")
public class ReconciliationActivitiesImpl implements ReconciliationActivities {

    private final TxnStore store;
    private final RowLoader loader;
    private final ReportWriter reports;

    public ReconciliationActivitiesImpl(TxnStore store, Path reportDir) {
        this.store = store;
        this.loader = new RowLoader(store);
        this.reports = new ReportWriter(store, reportDir);
    }

    @Override
    public int countRows(String fileRef) {
        return loader.countRows(fileRef);
    }

    @Override
    public SeedResult seedChunk(ChunkRequest req) {
        return loader.load(req, req.fileId());
    }

    @Override
    public int setCounter(String fileId) {
        String workflowId = Activity.getExecutionContext().getInfo().getWorkflowId();
        return store.initCounter(fileId, workflowId);
    }

    @Override
    public int getPending(String fileId) {
        return store.getPending(fileId);
    }

    @Override
    public int submitBatch(ChunkRequest req) {
        Activity.getExecutionContext().heartbeat(req.offset());

        int fromLine = req.offset();
        int toLine = req.offset() + req.size();
        return store.enqueueOutbound(req.fileId(), fromLine, toLine);
    }

    @Override
    public int markTimeouts(String fileId) {
        return store.markTimeouts(fileId);
    }

    @Override
    public ReportSummary writeReport(String fileId) {
        return reports.write(fileId);
    }
}
