package org.example.delivery.planning;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import org.example.delivery.planning.DraftGenerator.Agreement;
import org.example.delivery.planning.DraftGenerator.Area;
import org.example.delivery.planning.DraftGenerator.Method;
import org.example.delivery.planning.DraftGenerator.Parcel;
import org.example.delivery.planning.DraftGenerator.State;

final class DraftPreviewFixture {
    static final String NETWORK = "江岸网点";
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final Instant START = OffsetDateTime.parse("2026-11-25T08:00:00+08:00").toInstant();
    static final Instant END = OffsetDateTime.parse("2026-11-25T12:00:00+08:00").toInstant();
    static final DraftGenerator.Worker CHEN = new DraftGenerator.Worker("201", "小陈");
    static final DraftGenerator.Worker LI = new DraftGenerator.Worker("202", "小李");
    static final Area EAST = new Area("east", "东区", CHEN);
    static final Area WEST = new Area("west", "西区", LI);
    static final Area NORTH = new Area("north", "北区", null);
    static final Map<String, Area> AREAS = Map.of(EAST.id(), EAST, WEST.id(), WEST, NORTH.id(), NORTH);
    private static final Agreement HOME = new Agreement(Method.HOME, null, null);

    private static final List<Parcel> EAST_BATCH = IntStream.rangeClosed(1, 60)
            .mapToObj(number -> parcel(number, State.AVAILABLE, EAST.id(), HOME))
            .toList();

    private static final Parcel EAST_HOME = parcel(101, State.AVAILABLE, EAST.id(), HOME);
    private static final List<Parcel> MIXED_BATCH = List.of(
            EAST_HOME,
            parcel(102, State.AVAILABLE, EAST.id(), new Agreement(Method.LOCKER, "西区云柜", WEST.id())),
            parcel(103, State.AVAILABLE, WEST.id(), new Agreement(Method.SHOP, "东区便民店", EAST.id())),
            parcel(104, State.AVAILABLE, EAST.id(), new Agreement(Method.STATION_PICKUP, "江岸甲驿站", EAST.id())),
            parcel(105, State.AVAILABLE, null, HOME),
            parcel(106, State.ISOLATED, EAST.id(), HOME),
            parcel(107, State.DEPARTED, EAST.id(), HOME),
            parcel(108, State.EXPECTED, EAST.id(), HOME),
            parcel(109, State.AVAILABLE, NORTH.id(), HOME),
            EAST_HOME);

    private DraftPreviewFixture() {}

    static DraftGenerator.Draft generate(boolean chenOnLeave, Instant at) {
        return new DraftGenerator().generate(AREAS, EAST_BATCH, shift(chenOnLeave), at);
    }

    static DraftGenerator.Draft generateMixed() {
        return new DraftGenerator().generate(AREAS, MIXED_BATCH, shift(false), START);
    }

    private static DraftGenerator.Shift shift(boolean chenOnLeave) {
        var chenStatus = chenOnLeave ? DraftGenerator.DutyStatus.ON_LEAVE : DraftGenerator.DutyStatus.ON_DUTY;
        return new DraftGenerator.Shift(START, END, List.of(
                new DraftGenerator.ShiftMember(CHEN.id(), chenStatus),
                new DraftGenerator.ShiftMember(LI.id(), DraftGenerator.DutyStatus.ON_DUTY)));
    }

    private static Parcel parcel(int number, State state, String areaId, Agreement agreement) {
        return new Parcel(String.format(Locale.ROOT, "00072605%04d", number), state, areaId, agreement);
    }
}
