package io.aura.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PageTest {

    @Test
    void rejectsInvalidPageMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() -> Page.exact(List.of(), 0, 20, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Page.exact(List.of(), 1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Page.exact(List.of(), 1, Page.MAX_PAGE_SIZE + 1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> Page.exact(List.of(), 1, 20, -1));
    }

    // --- totalPages ---

    @Test
    void totalPages_exactDivision() {
        Page<String> page = Page.exact(List.of(), 1, 20, 100);
        assertThat(page.totalPages()).hasValue(5);
    }

    @Test
    void totalPages_withRemainder() {
        Page<String> page = Page.exact(List.of(), 1, 20, 101);
        assertThat(page.totalPages()).hasValue(6);
    }

    @Test
    void totalPages_singleItem() {
        Page<String> page = Page.exact(List.of(), 1, 20, 1);
        assertThat(page.totalPages()).hasValue(1);
    }

    // --- hasNext ---

    @Test
    void hasNext_trueWhenNotOnLastPage() {
        Page<String> page = Page.exact(List.of(), 1, 20, 100); // page 1 of 5
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void hasNext_falseOnLastPage() {
        Page<String> page = Page.exact(List.of(), 5, 20, 100); // page 5 of 5
        assertThat(page.hasNext()).isFalse();
    }

    // --- hasPrev ---

    @Test
    void hasPrev_falseOnFirstPage() {
        Page<String> page = Page.exact(List.of(), 1, 20, 100);
        assertThat(page.hasPrev()).isFalse();
    }

    @Test
    void hasPrev_trueOnSecondPage() {
        Page<String> page = Page.exact(List.of(), 2, 20, 100);
        assertThat(page.hasPrev()).isTrue();
    }

    // --- empty page ---

    @Test
    void emptyPage_totalIsZero() {
        Page<String> page = Page.exact(List.of(), 1, 20, 0);
        assertThat(page.total()).hasValue(0);
        assertThat(page.list()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrev()).isFalse();
    }

    @Test
    void emptyPage_totalPagesIsZero() {
        Page<String> page = Page.exact(List.of(), 1, 20, 0);
        assertThat(page.totalPages()).hasValue(0);
    }

    // --- accessors ---

    @Test
    void accessors_returnConstructorValues() {
        List<String> items = List.of("a", "b");
        Page<String> page = Page.exact(items, 3, 10, 50);

        assertThat(page.list()).isEqualTo(items);
        assertThat(page.pageNum()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.total()).hasValue(50);
    }

    @Test
    void unknownTotalRemainsExplicitWhileHasNextIsKnown() {
        Page<String> page = Page.withoutTotal(List.of("a", "b"), 1, 2, true);

        assertThat(page.total()).isEmpty();
        assertThat(page.totalPages()).isEmpty();
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void totalPagesRejectsCountsOutsideIntegerPageRangeWithoutOverflow() {
        Page<String> page = Page.exact(List.of(), 1, 2, Long.MAX_VALUE);

        assertThatThrownBy(page::totalPages)
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("integer range");
    }
}
