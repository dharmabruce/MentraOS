package com.mentra.bluetoothsdk.sgcs.inmo;

import java.util.Arrays;

/**
 * Typed answer to a binding query (operation 0). Owns a private copy of any identifier; close() wipes it.
 * Imported from go3-sideview@907d099:app/src/main/java/dev/go3/mirror/setup/BindingReply.java (diagnostics removed).
 * Ported from lens-spark@79d33c2 (protocol/BindingReply.java).
 */
public final class BindingReply implements AutoCloseable {
    public enum Kind { ACCESS_FOUND, UNBOUND, IDENTIFIER_MISSING, VENDOR_ERROR, MALFORMED, UNRELATED, LIMIT_EXCEEDED }

    public final Kind kind;
    private byte[] identifier;

    BindingReply(Kind kind) {
        this(kind, null);
    }

    BindingReply(Kind kind, byte[] identifier) {
        this.kind = kind;
        this.identifier = identifier;
    }

    public synchronized byte[] copyIdentifier() {
        if (kind != Kind.ACCESS_FOUND || identifier == null) throw new IllegalStateException("No validated access value");
        return identifier.clone();
    }

    /** Constant-time comparison against an expected identifier, without exposing the stored one. */
    public synchronized boolean matches(byte[] expected) {
        return kind == Kind.ACCESS_FOUND && identifier != null && expected != null
                && java.security.MessageDigest.isEqual(identifier, expected);
    }

    @Override public synchronized void close() {
        if (identifier != null) Arrays.fill(identifier, (byte) 0);
        identifier = null;
    }

    @Override public String toString() {
        return "BindingReply{" + kind + ", identifier=redacted}";
    }
}
