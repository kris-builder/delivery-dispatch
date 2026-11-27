package org.example.delivery.planning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Computes a draft from a single network's batch and shift snapshots. */
public final class DraftGenerator {
    public Draft generate(Map<String, Area> areas, List<Parcel> batch, Shift shift, Instant at) {
        Set<String> onDuty = onDutyWorkers(shift, at);
        Map<String, Parcel> seen = new HashMap<>();
        Map<Area, List<Item>> groups = new LinkedHashMap<>();
        List<Excluded> excluded = new ArrayList<>();

        for (Parcel parcel : batch) {
            Parcel previous = seen.putIfAbsent(parcel.waybillNo(), parcel);
            if (previous != null) {
                if (!previous.equals(parcel)) {
                    throw new IllegalArgumentException("Conflicting snapshot for waybill " + parcel.waybillNo());
                }
                continue;
            }

            ExclusionReason exclusion = exclusionFor(parcel);
            if (exclusion != null) {
                excluded.add(new Excluded(parcel, exclusion));
                continue;
            }

            String targetAreaId = parcel.agreement().method() == Method.HOME
                    ? parcel.addressAreaId() : parcel.agreement().pointAreaId();
            Area area = targetAreaId == null ? null : areas.get(targetAreaId);
            groups.computeIfAbsent(area, ignored -> new ArrayList<>()).add(suggest(parcel, area, onDuty));
        }

        List<Group> resultGroups = groups.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new Draft(resultGroups, List.copyOf(excluded));
    }

    private static Set<String> onDutyWorkers(Shift shift, Instant at) {
        if (at.isBefore(shift.startsAt()) || !at.isBefore(shift.endsAt())) {
            return Set.of();
        }
        return shift.members().stream()
                .filter(member -> member.status() == DutyStatus.ON_DUTY)
                .map(ShiftMember::workerId)
                .collect(Collectors.toSet());
    }

    private static ExclusionReason exclusionFor(Parcel parcel) {
        return switch (parcel.state()) {
            case EXPECTED -> ExclusionReason.NOT_RECEIVED;
            case ISOLATED -> ExclusionReason.ISOLATED;
            case DEPARTED -> ExclusionReason.DEPARTED;
            case AVAILABLE -> parcel.agreement().method() == Method.STATION_PICKUP
                    ? ExclusionReason.STATION_TRANSFER : null;
        };
    }

    private static Item suggest(Parcel parcel, Area area, Set<String> onDuty) {
        if (area == null) {
            return new Item(parcel, null, PendingReason.AREA_UNCONFIRMED);
        }
        Worker worker = area.regularWorker();
        if (worker == null) {
            return new Item(parcel, null, PendingReason.NO_REGULAR_WORKER);
        }
        if (!onDuty.contains(worker.id())) {
            return new Item(parcel, null, PendingReason.REGULAR_WORKER_UNAVAILABLE);
        }
        return new Item(parcel, worker, null);
    }

    public enum DutyStatus { ON_DUTY, ON_LEAVE }
    public enum State { EXPECTED, AVAILABLE, ISOLATED, DEPARTED }
    public enum Method { HOME, LOCKER, SHOP, STATION_PICKUP }
    public enum PendingReason { AREA_UNCONFIRMED, NO_REGULAR_WORKER, REGULAR_WORKER_UNAVAILABLE }
    public enum ExclusionReason { STATION_TRANSFER, NOT_RECEIVED, ISOLATED, DEPARTED }

    public record Worker(String id, String displayName) {}
    public record Area(String id, String name, Worker regularWorker) {}
    public record Shift(Instant startsAt, Instant endsAt, List<ShiftMember> members) {}
    public record ShiftMember(String workerId, DutyStatus status) {}

    public record Agreement(Method method, String destinationName, String pointAreaId) {}
    public record Parcel(String waybillNo, State state, String addressAreaId, Agreement agreement) {}

    public record Item(Parcel parcel, Worker proposedWorker, PendingReason pendingReason) {
        public String waybillNo() {
            return parcel.waybillNo();
        }
    }

    public record Excluded(Parcel parcel, ExclusionReason reason) {}

    public record Group(Area area, List<Item> items) {
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

    public record Draft(List<Group> groups, List<Excluded> excluded) {
        public List<Item> items() {
            return groups.stream().flatMap(group -> group.items().stream()).toList();
        }

        public int total() {
            return groups.stream().mapToInt(Group::total).sum();
        }

        public long assigned() {
            return groups.stream().mapToLong(Group::assigned).sum();
        }

        public long pending() {
            return total() - assigned();
        }
    }
}
