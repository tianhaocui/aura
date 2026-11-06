package io.aura.mcp;

/** Snapshot of bounded MCP SSE hub usage and failure counters. */
public record McpSseMetrics(
        long activeConnections,
        long closingConnections,
        long histories,
        long queuedEvents,
        long queuedBytes,
        long overflowedConnections,
        long replayGaps,
        long writeFailures) { }
