package org.example.delivery.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.example.delivery.planning.DraftGenerator.Excluded;
import org.example.delivery.planning.DraftGenerator.ExclusionReason;
import org.example.delivery.planning.DraftGenerator.Group;
import org.example.delivery.planning.DraftGenerator.Item;
import org.example.delivery.planning.DraftGenerator.PendingReason;
import org.junit.jupiter.api.Test;

class MixedDestinationDraftTests {
    @Test
    void usesAddressForHomeAndPointAreaForLockerAndShop() {
        var draft = DraftPreviewFixture.generateMixed();
        var east = group(draft, "east");
        var west = group(draft, "west");

        assertEquals(List.of("000726050101", "000726050103"), east.items().stream().map(Item::waybillNo).toList());
        assertEquals(List.of("000726050102"), west.items().stream().map(Item::waybillNo).toList());

        var shop = east.items().get(1);
        assertEquals("west", shop.parcel().addressAreaId());
        assertEquals("东区便民店", shop.parcel().agreement().destinationName());
        assertEquals(DraftPreviewFixture.CHEN, shop.proposedWorker());

        var locker = west.items().getFirst();
        assertEquals("east", locker.parcel().addressAreaId());
        assertEquals("西区云柜", locker.parcel().agreement().destinationName());
        assertEquals(DraftPreviewFixture.LI, locker.proposedWorker());
    }

    @Test
    void listsStationTransferAndIneligibleParcelsOutsideDirectDraft() {
        var draft = DraftPreviewFixture.generateMixed();
        var exclusions = draft.excluded().stream().collect(Collectors.toMap(
                item -> item.parcel().waybillNo(), Excluded::reason));

        assertEquals(Map.of(
                "000726050104", ExclusionReason.STATION_TRANSFER,
                "000726050106", ExclusionReason.ISOLATED,
                "000726050107", ExclusionReason.DEPARTED,
                "000726050108", ExclusionReason.NOT_RECEIVED), exclusions);
        assertEquals("江岸甲驿站", draft.excluded().getFirst().parcel().agreement().destinationName());
        assertEquals(9, draft.total() + draft.excluded().size());
    }

    @Test
    void reportsPendingReasonsAndReconcilesGroupCountsWithoutDuplicateWaybills() {
        var draft = DraftPreviewFixture.generateMixed();
        var unconfirmed = draft.groups().stream().filter(group -> group.area() == null).findFirst().orElseThrow();
        var north = group(draft, "north");

        assertEquals("000726050105", unconfirmed.items().getFirst().waybillNo());
        assertEquals(PendingReason.AREA_UNCONFIRMED, unconfirmed.items().getFirst().pendingReason());
        assertEquals(PendingReason.NO_REGULAR_WORKER, north.items().getFirst().pendingReason());
        assertEquals(List.of(2, 1, 1, 1), draft.groups().stream().map(Group::total).toList());
        assertEquals(List.of(2L, 1L, 0L, 0L), draft.groups().stream().map(Group::assigned).toList());
        assertEquals(List.of(0L, 0L, 1L, 1L), draft.groups().stream().map(Group::pending).toList());
        assertEquals(5, draft.total());
        assertEquals(3, draft.assigned());
        assertEquals(2, draft.pending());
    }

    private static Group group(DraftGenerator.Draft draft, String areaId) {
        return draft.groups().stream()
                .filter(group -> group.area() != null && group.area().id().equals(areaId))
                .findFirst().orElseThrow();
    }
}
