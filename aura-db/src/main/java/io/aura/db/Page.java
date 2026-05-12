package io.aura.db;

import java.util.List;

public record Page<T>(List<T> list, int pageNum, int pageSize, long total) {

    public int totalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }

    public boolean hasNext() {
        return pageNum < totalPages();
    }

    public boolean hasPrev() {
        return pageNum > 1;
    }

    @SuppressWarnings("unchecked")
    static <T> Page<T> empty(int pageNum, int pageSize) {
        return new Page<>(List.of(), pageNum, pageSize, 0);
    }
}
