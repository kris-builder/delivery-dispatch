package org.example.delivery.fulfillment;

import java.util.Objects;

/** Reads the station-owned rule for this network and verifies that its target is an active area. */
public interface InboundAreaMatcher {
    Match match(long networkId, String street, String community);

    record Area(long id, String name) {
        public Area {
            Objects.requireNonNull(name);
        }
    }

    enum PendingReason {
        MISSING_ADDRESS, NO_EFFECTIVE_RULE, MATCH_UNCONFIRMED
    }

    record Match(Area area, PendingReason pendingReason) {
        public Match {
            if ((area == null) == (pendingReason == null)) {
                throw new IllegalArgumentException("Specify an area or a pending reason");
            }
        }
    }
}
