package io.aura.web;

public interface CodedException {
    String code();

    default int status() {
        return 400;
    }

    default String field() {
        return null;
    }
}
