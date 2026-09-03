package io.aura.snapshot;

import java.util.List;
import java.util.Map;

public record ExceptionSnapshot(
        int schemaVersion,
        String snapshotId,
        String createdAt,
        RequestSnapshot request,
        RouteSnapshot route,
        ExceptionInfo exception,
        ResponseSnapshot response,
        EnvSnapshot environment,
        List<ExternalCallSnapshot> externalCalls,
        Map<String, Object> artifacts
) {
    public record RequestSnapshot(
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> headers,
            Object body,
            String ip,
            String requestId
    ) {}

    public record RouteSnapshot(
            String method,
            String pathPattern,
            Map<String, String> pathParams,
            String handler
    ) {}

    public record ExceptionInfo(
            String type,
            String message,
            String code,
            String field,
            Integer status,
            String rootCauseType,
            String rootCauseMessage,
            String stackTrace
    ) {}

    public record ResponseSnapshot(
            int status,
            Map<String, String> headers,
            String contentType,
            Object body
    ) {}

    public record EnvSnapshot(
            String auraVersion,
            String auraEnv,
            String javaVersion,
            String osName,
            String timezone,
            String locale,
            Map<String, String> properties,
            Map<String, String> env
    ) {}

    public record ExternalCallSnapshot(
            String id,
            String name,
            String protocol,
            String method,
            String url,
            Map<String, String> requestHeaders,
            Object requestBody,
            int responseStatus,
            Map<String, String> responseHeaders,
            Object responseBody,
            long durationMs,
            String startedAt
    ) {}
}
