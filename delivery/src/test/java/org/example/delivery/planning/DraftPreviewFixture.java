package org.example.delivery.planning;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

final class DraftPreviewFixture {
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final Instant START = OffsetDateTime.parse("2026-11-25T08:00:00+08:00").toInstant();
    static final Instant END = OffsetDateTime.parse("2026-11-25T12:00:00+08:00").toInstant();
    static final DraftGenerator.Worker CHEN = new DraftGenerator.Worker("201", "小陈");
    private static final DraftGenerator.Worker LI = new DraftGenerator.Worker("202", "小李");
    private static final DraftGenerator.Area EAST = new DraftGenerator.Area("江岸网点", "东区", CHEN);
    private static final List<String> CONFIRMED_HOME_WAYBILLS = IntStream.rangeClosed(1, 60)
            .mapToObj(number -> String.format(Locale.ROOT, "00072605%04d", number))
            .toList();

    private DraftPreviewFixture() {}

    static DraftGenerator.Draft generate(boolean chenOnLeave, Instant at) {
        var chenStatus = chenOnLeave ? DraftGenerator.DutyStatus.ON_LEAVE : DraftGenerator.DutyStatus.ON_DUTY;
        var shift = new DraftGenerator.Shift(START, END, List.of(
                new DraftGenerator.ShiftMember(CHEN.id(), chenStatus),
                new DraftGenerator.ShiftMember(LI.id(), DraftGenerator.DutyStatus.ON_DUTY)));
        return new DraftGenerator().generate(EAST, CONFIRMED_HOME_WAYBILLS, shift, at);
    }
}
