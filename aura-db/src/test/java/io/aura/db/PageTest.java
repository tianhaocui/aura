package io.aura.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PageTest {

    // --- totalPages ---

    @Test
    void totalPages_exactDivision() {
        Page<String> page = new Page<>(List.of(), 1, 20, 100);
        assertThat(page.totalPages()).isEqualTo(5);
    }

    @Test
    void totalPages_withRemainder() {
        Page<String> page = new Page<>(List.of(), 1, 20, 101);
        assertThat(page.totalPages()).isEqualTo(6);
    }

    @Test
    void totalPages_singleItem() {
        Page<String> page = new Page<>(List.of(), 1, 20, 1);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    // --- hasNext ---

    @Test
    void hasNext_trueWhenNotOnLastPage() {
        Page<String> page = new Page<>(List.of(), 1, 20, 100); // page 1 of 5
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void hasNext_falseOnLastPage() {
        Page<String> page = new Page<>(List.of(), 5, 20, 100); // page 5 of 5
        assertThat(page.hasNext()).isFalse();
    }

    // --- hasPrev ---

    @Test
    void hasPrev_falseOnFirstPage() {
        Page<String> page = new Page<>(List.of(), 1, 20, 100);
        assertThat(page.hasPrev()).isFalse();
    }

    @Test
    void hasPrev_trueOnSecondPage() {
        Page<String> page = new Page<>(List.of(), 2, 20, 100);
        assertThat(page.hasPrev()).isTrue();
    }

    // --- empty page ---

    @Test
    void emptyPage_totalIsZero() {
        Page<String> page = new Page<>(List.of(), 1, 20, 0);
        assertThat(page.total()).isEqualTo(0);
        assertThat(page.list()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrev()).isFalse();
    }

    @Test
    void emptyPage_totalPagesIsZero() {
        Page<String> page = new Page<>(List.of(), 1, 20, 0);
        assertThat(page.totalPages()).isEqualTo(0);
    }

    // --- accessors ---

    @Test
    void accessors_returnConstructorValues() {
        List<String> items = List.of("a", "b");
        Page<String> page = new Page<>(items, 3, 10, 50);

        assertThat(page.list()).isEqualTo(items);
        assertThat(page.pageNum()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(50);
    }
}
