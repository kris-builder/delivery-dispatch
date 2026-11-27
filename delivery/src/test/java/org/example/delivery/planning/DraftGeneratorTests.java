package org.example.delivery.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class DraftGeneratorTests {
    @Test
    void assignsAllSixtyEastParcelsToTheOnDutyRegularCourier() {
        var draft = DraftPreviewFixture.generate(false, DraftPreviewFixture.START);

        assertEquals(60, draft.total());
        assertEquals(60, draft.assigned());
        assertEquals(0, draft.pending());
        assertEquals("江岸网点", draft.networkName());
        assertEquals("东区", draft.areaName());
        assertEquals("000726050001", draft.items().getFirst().waybillNo());
        assertEquals(60, draft.items().stream().map(DraftGenerator.Item::waybillNo).distinct().count());
        assertTrue(draft.items().stream().allMatch(item -> item.proposedWorker().equals(DraftPreviewFixture.CHEN)
                && item.pendingReason() == null));
    }

    @Test
    void leavesEachParcelPendingWhenChenIsOnLeaveEvenThoughLiIsOnDuty() {
        var draft = DraftPreviewFixture.generate(true, DraftPreviewFixture.START);

        assertEquals(60, draft.total());
        assertEquals(0, draft.assigned());
        assertEquals(60, draft.pending());
        for (var item : draft.items()) {
            assertNull(item.proposedWorker());
            assertEquals(DraftGenerator.PendingReason.REGULAR_WORKER_UNAVAILABLE, item.pendingReason());
        }
    }

    @Test
    void usesInclusiveStartAndExclusiveEndInBeijingTime() {
        var start = DraftPreviewFixture.START;
        var end = DraftPreviewFixture.END;

        assertEquals(Instant.parse("2026-11-25T00:00:00Z"), start);
        assertEquals(Instant.parse("2026-11-25T04:00:00Z"), end);
        assertEquals(60, DraftPreviewFixture.generate(false, start.minusNanos(1)).pending());
        assertEquals(60, DraftPreviewFixture.generate(false, start).assigned());
        assertEquals(60, DraftPreviewFixture.generate(false, end.minusNanos(1)).assigned());
        assertEquals(60, DraftPreviewFixture.generate(false, end).pending());
    }
}
