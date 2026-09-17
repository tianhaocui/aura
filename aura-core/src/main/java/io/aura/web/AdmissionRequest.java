package io.aura.web;

/** Stable request facts supplied to a replaceable admission policy. */
public record AdmissionRequest(String method, String path, String routePattern,
                               String clientIp, String apiKey) { }
