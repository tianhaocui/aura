package io.aura.web;

import java.io.IOException;

/**
 * The body is larger than this application agreed to read. It stays an IOException so every handler
 * that already catches one keeps catching it, and it is its own type so the answer can be the 413
 * that tells the caller to send less rather than the 500 that invites them to send the same thing
 * again.
 */
class RequestBodyTooLargeException extends IOException {

    RequestBodyTooLargeException(String message) {
        super(message);
    }
}
