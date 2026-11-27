package org.example.delivery.planning;

import java.time.Instant;
import java.util.List;

/** Computes suggestions for one area's confirmed home-delivery parcels still at the network. */
public final class DraftGenerator {
    public Draft generate(Area area, List<String> inStationWaybills, Shift shift, Instant at) {
        Worker regularWorker = area.regularWorker();
        boolean withinShift = !at.isBefore(shift.startsAt()) && at.isBefore(shift.endsAt());
        boolean regularOnDuty = withinShift && shift.members().stream().anyMatch(member ->
                member.workerId().equals(regularWorker.id()) && member.status() == DutyStatus.ON_DUTY);
        Worker proposedWorker = regularOnDuty ? regularWorker : null;
        PendingReason reason = proposedWorker == null ? PendingReason.REGULAR_WORKER_UNAVAILABLE : null;
        List<Item> items = inStationWaybills.stream()
                .distinct()
                .map(waybillNo -> new Item(waybillNo, proposedWorker, reason))
                .toList();
        return new Draft(area.networkName(), area.name(), items);
    }

    public enum DutyStatus { ON_DUTY, ON_LEAVE }

    public enum PendingReason { REGULAR_WORKER_UNAVAILABLE }

    public record Worker(String id, String displayName) {}

    public record Area(String networkName, String name, Worker regularWorker) {}

    public record Shift(Instant startsAt, Instant endsAt, List<ShiftMember> members) {}

    public record ShiftMember(String workerId, DutyStatus status) {}

    public record Item(String waybillNo, Worker proposedWorker, PendingReason pendingReason) {}

    public record Draft(String networkName, String areaName, List<Item> items) {
        public int total() {
            return items.size();
        }

        public long assigned() {
            return items.stream().filter(item -> item.proposedWorker() != null).count();
        }

        public long pending() {
            return total() - assigned();
        }
    }
}
