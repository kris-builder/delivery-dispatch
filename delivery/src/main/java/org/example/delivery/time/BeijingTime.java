package org.example.delivery.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

public final class BeijingTime {
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);

    private BeijingTime() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(OFFSET).truncatedTo(ChronoUnit.MICROS);
    }

    public static LocalDateTime forDatabase(Instant instant) {
        return LocalDateTime.ofInstant(instant, OFFSET).truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime fromDatabase(LocalDateTime timestamp) {
        return timestamp.atOffset(OFFSET);
    }
}
