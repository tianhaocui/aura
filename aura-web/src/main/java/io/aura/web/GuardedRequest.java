package io.aura.web;

/**
 * Transport-neutral view of one in-flight request, used by the pre-route guard chain.
 *
 * <p>{@code UndertowStarter} and {@code TestClient} each supply one implementation, so a host,
 * CSRF, CORS, or rate-limit decision asserted in a millisecond test is the same code a real
 * socket runs.</p>
 */
public interface GuardedRequest {

    String method();

    /**
     * Request path without its query string, because that is what a route template
     * matches. A transport that hands out {@code getRequestURI()} and a transport that
     * hands out the raw target are not interchangeable here: admission rules keyed on
     * the matched template silently stop firing the moment a query string appears.
     */
    String path();

    /** Request header value matched case-insensitively, or null when absent. */
    String header(String name);

    /** Request cookie value, or null when the cookie is absent. */
    String cookie(String name);

    /** True when the request arrived over TLS. */
    boolean secure();

    /** Host the client asked for without its port, or null when the transport cannot resolve one. */
    String hostName();

    /** {@code host[:port]} exactly as the transport received it. */
    String hostAndPort();

    /** Request scheme such as {@code http}; never null. */
    String scheme();

    /** Address of the direct TCP peer, or null when unknown. */
    String peerIp();

    /** Sets one response header, replacing any earlier value. */
    void responseHeader(String name, String value);

    /** Appends one response header value, keeping earlier values. */
    void addResponseHeader(String name, String value);

    /** Runs when the request finishes, whether the chain rejected it or a handler served it. */
    void onComplete(Runnable action);
}
