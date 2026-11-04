package com.example.recon.design.chunk.activity;

import com.example.recon.dto.ChunkRequest;
import com.example.recon.dto.ChunkSummary;
import com.example.recon.dto.ReportSummary;
import com.example.recon.dto.StageResult;

import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ChunkActivities {

    int countRows(String fileRef);

    StageResult stageChunk(ChunkRequest request, String counterKey);

    int submitChunk(ChunkRequest request);

    ChunkSummary finishChunk(String counterKey);

    ReportSummary writeReport(String fileId);
}
