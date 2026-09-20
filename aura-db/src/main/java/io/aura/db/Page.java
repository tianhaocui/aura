package io.aura.db;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record Page<T>(List<T> list, int pageNum, int pageSize,
                      OptionalLong total, boolean hasNext) {

    public static final int MAX_PAGE_SIZE = 500;

    public Page {
        if (list == null) throw new IllegalArgumentException("list must not be null");
        if (pageNum < 1) throw new IllegalArgumentException("pageNum must be at least 1: " + pageNum);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ": " + pageSize);
        }
        total = total == null ? OptionalLong.empty() : total;
        if (total.isPresent()) {
            if (total.getAsLong() < 0) {
                throw new IllegalArgumentException("total must not be negative: " + total.getAsLong());
            }
            boolean expectedHasNext = (long) pageNum * pageSize < total.getAsLong();
            if (hasNext != expectedHasNext) {
                throw new IllegalArgumentException("hasNext is inconsistent with exact total");
            }
        }
        list = Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static <T> Page<T> exact(List<T> list, int pageNum, int pageSize, long total) {
        return new Page<>(list, pageNum, pageSize, OptionalLong.of(total),
                (long) pageNum * pageSize < total);
    }

    public static <T> Page<T> withoutTotal(List<T> list, int pageNum, int pageSize,
                                            boolean hasNext) {
        return new Page<>(list, pageNum, pageSize, OptionalLong.empty(), hasNext);
    }

    public OptionalInt totalPages() {
        if (total.isEmpty()) return OptionalInt.empty();
        long value = total.getAsLong();
        long pages = value / pageSize + (value % pageSize == 0 ? 0 : 1);
        if (pages > Integer.MAX_VALUE) {
            throw new ArithmeticException("total page count exceeds integer range");
        }
        return OptionalInt.of((int) pages);
    }

    public boolean hasPrev() {
        return pageNum > 1;
    }

    @SuppressWarnings("unchecked")
    static <T> Page<T> empty(int pageNum, int pageSize) {
        return Page.exact(List.of(), pageNum, pageSize, 0);
    }
}
