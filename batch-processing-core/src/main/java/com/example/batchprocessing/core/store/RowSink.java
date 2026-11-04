package com.example.batchprocessing.core.store;

import java.io.IOException;

@FunctionalInterface
public interface RowSink {

    void accept(String txnId, int lineNo, String state, String errorCode) throws IOException;
}
