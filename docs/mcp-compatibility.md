# MCP 1.0 compatibility matrix

Aura 1.0 has one canonical MCP protocol contract. The in-process router, stdio
transport, streamable HTTP transport, SSE notifications, and generated bridge
all expose the same protocol version and route metadata.

| Surface | Supported version | Handshake | Discovery | Invocation | Auth boundary |
|---|---|---|---|---|---|
| In-process `McpRouterSpec` | `2025-03-26` | `initialize`/`initialized` | tools, resources, prompts | `tools/call` | application authenticator/authorizer |
| stdio (`--mcp-stdio`) | `2025-03-26` | JSON-RPC line frames | tools, resources, prompts | `tools/call` | application authenticator/authorizer |
| streamable HTTP (`McpModule.http`) | `2025-03-26` | `initialize` + `Mcp-Session-Id` | tools, resources, prompts | JSON or request-scoped SSE | HTTP auth plus application authenticator/authorizer |
| HTTP SSE (`McpModule.http().sse()`) | `2025-03-26` | same HTTP session | same HTTP catalog | bounded replay/notification | same HTTP auth boundary |
| generated bridge/npm package | `2025-03-26` | forwards initialize | schema-derived tools | schema-derived HTTP calls | schema token/application auth |

## Stable protocol behavior

- `ping` is supported on every in-process/stdio/HTTP transport.
- Unsupported protocol versions are rejected during initialize with stable
  `MCP_PROTOCOL_VERSION_UNSUPPORTED` semantics; a request cannot silently
  downgrade to an unknown version.
- Missing or wrong-typed method fields are `-32602 Invalid params` and never
  invoke a tool implementation.
- Provider/tool failures never expose raw exception messages or stack traces.
- Authentication failures use `MCP_AUTHENTICATION_REQUIRED` and permission
  failures use `MCP_PERMISSION_DENIED`.
- Bounded responses that exceed the configured transport budget use
  `MCP_RESPONSE_TOO_LARGE`.
- The custom `McpRouterSpec` implementation receives the same resolved identity
  and permission checker on stdio and HTTP; it must enforce that checker before
  executing protected tools.

The executable compatibility checks live in `McpHttpTransportTest` and
`McpRouterSpecIntegrationTest`; this document is the compact AI-facing lookup
surface, not a second protocol definition.
