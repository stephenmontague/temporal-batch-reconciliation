package com.example.recon.view;

public final class TemporalUiLinks {

    public static String workflow(String uiBaseUrl, String namespace, String workflowId) {
        String base = uiBaseUrl.endsWith("/") ? uiBaseUrl.substring(0, uiBaseUrl.length() - 1) : uiBaseUrl;
        return base + "/namespaces/" + namespace + "/workflows/" + workflowId;
    }

    private TemporalUiLinks() {}
}
