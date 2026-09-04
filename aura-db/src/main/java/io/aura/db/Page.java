package io.aura.db;

import java.util.List;

public record Page<T>(List<T> list, int pageNum, int pageSize, long total) {

    public static final int MAX_PAGE_SIZE = 500;

    public Page {
        if (list == null) throw new IllegalArgumentException("list must not be null");
        if (pageNum < 1) throw new IllegalArgumentException("pageNum must be at least 1: " + pageNum);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ": " + pageSize);
        }
        if (total < 0) throw new IllegalArgumentException("total must not be negative: " + total);
    }

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
