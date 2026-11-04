package com.example.recon.service;

import java.io.IOException;

public interface RowSink {
    void accept(String txnId, int lineNo, String state, String errorCode) throws IOException;
}
