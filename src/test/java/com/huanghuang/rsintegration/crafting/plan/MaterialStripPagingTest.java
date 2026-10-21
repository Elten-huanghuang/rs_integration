package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialStripPagingTest {

    @Test
    void computesStablePageCounts() {
        assertEquals(1, MaterialStripPaging.pageCount(0, 8));
        assertEquals(1, MaterialStripPaging.pageCount(8, 8));
        assertEquals(2, MaterialStripPaging.pageCount(9, 8));
        assertEquals(3, MaterialStripPaging.pageCount(17, 8));
        assertEquals(1, MaterialStripPaging.pageCount(17, 0));
    }

    @Test
    void clampsAndSlicesOnlyTheCurrentPage() {
        assertEquals(0, MaterialStripPaging.clampPage(-4, 17, 8));
        assertEquals(2, MaterialStripPaging.clampPage(99, 17, 8));
        assertEquals(8, MaterialStripPaging.pageStart(1, 17, 8));
        assertEquals(16, MaterialStripPaging.pageEnd(1, 17, 8));
        assertEquals(16, MaterialStripPaging.pageStart(2, 17, 8));
        assertEquals(17, MaterialStripPaging.pageEnd(2, 17, 8));
    }

    @Test
    void movingPastEitherBoundaryStaysOnAValidPage() {
        assertEquals(0, MaterialStripPaging.movePage(0, -1, 17, 8));
        assertEquals(2, MaterialStripPaging.movePage(2, 1, 17, 8));
        assertEquals(1, MaterialStripPaging.movePage(0, 9, 17, 8));
    }
}
