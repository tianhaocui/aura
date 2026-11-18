package io.aura.contract;

/**
 * The explicit, three-state access decision of a capability. There is no fourth state:
 * anonymous access is allowed only because {@link #PUBLIC} was declared, never because a
 * permission happened to be absent.
 *
 * <p>{@link #authenticated()} and {@link #permissionId()} are the one mapping onto the
 * {@link Route} vocabulary ({@code authenticated} + {@code permission}); every projection
 * uses it instead of re-deciding per protocol.
 */
public sealed interface AccessPolicy permits AccessPolicy.Public, AccessPolicy.Authenticated, AccessPolicy.Permission {

    /** Anonymous invocation is explicitly allowed. */
    AccessPolicy PUBLIC = new Public();
    /** An identity is required; no specific permission is. */
    AccessPolicy AUTHENTICATED = new Authenticated();

    /** An identity holding {@code id} is required. */
    static AccessPolicy permission(String id) { return new Permission(id); }

    boolean authenticated();
    String permissionId();

    record Public() implements AccessPolicy {
        @Override public boolean authenticated() { return false; }
        @Override public String permissionId() { return null; }
    }

    record Authenticated() implements AccessPolicy {
        @Override public boolean authenticated() { return true; }
        @Override public String permissionId() { return null; }
    }

    record Permission(String id) implements AccessPolicy {
        public Permission {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("permission id must not be blank");
            }
            id = id.trim();
        }
        @Override public boolean authenticated() { return true; }
        @Override public String permissionId() { return id; }
    }
}