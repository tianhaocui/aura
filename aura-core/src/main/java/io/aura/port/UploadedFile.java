package io.aura.port;

public record UploadedFile(String fileName, byte[] data, String contentType) {

    public String name() { return fileName; }

    public long size() { return data != null ? data.length : 0; }
}
