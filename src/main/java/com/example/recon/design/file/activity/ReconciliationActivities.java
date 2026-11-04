package com.example.recon.design.file.activity;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ReportSummary;
import com.example.recon.dto.SeedResult;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ReconciliationActivities {

    int countRows(String fileRef);

    SeedResult seedChunk(ChunkRequest request);

    int setCounter(String fileId);

    int getPending(String fileId);

    int submitBatch(ChunkRequest request);

    int markTimeouts(String fileId);

    ReportSummary writeReport(String fileId);
}
