package io.aura.web;

import java.io.Closeable;

/**
 * Server-Sent Events emitter. Obtain via {@code ctx.sse()}.
 *
 * <pre>{@code
 * r.get("/stream", ctx -> {
 *     SseEmitter sse = ctx.sse();
 *     sse.send("hello");
 *     sse.send("data", "world");
 *     sse.send("data", "done", "msg-1");  // with event id
 *     sse.close();
 * });
 * }</pre>
 */
public interface SseEmitter extends Closeable {

    void send(String data) throws Exception;

    void send(String event, String data) throws Exception;

    void send(String event, String data, String id) throws Exception;

    void close();
}
