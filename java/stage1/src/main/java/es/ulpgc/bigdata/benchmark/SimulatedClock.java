package es.ulpgc.bigdata.benchmark;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Clock that moves forward a fixed step every time it is asked for the time.
 *
 * For the benchmark of the "time" structure: with the real clock, the hundreds of books
 * would be saved in seconds and would all end up in the same YYYYMMDD/HH folder.
 * With this clock, each save() happens "a while later", as if the ingestion lasted days,
 * and always in the same way: the benchmark is reproducible.
 */
public final class SimulatedClock extends Clock {

    private final ZoneId zone;
    private final Duration step;
    private Instant current;

    public SimulatedClock(LocalDateTime start, Duration step, ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
        this.step = Objects.requireNonNull(step, "step");
        this.current = start.atZone(zone).toInstant();
    }

    /** 10 books per hour starting on 1 January 2026: about 240 books per day folder. */
    public static SimulatedClock tenBooksPerHour() {
        return new SimulatedClock(LocalDateTime.of(2026, 1, 1, 0, 0), Duration.ofMinutes(6), ZoneId.of("UTC"));
    }

    @Override
    public synchronized Instant instant() {
        Instant now = current;
        current = current.plus(step);
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        throw new UnsupportedOperationException("El benchmark usa una zona fija");
    }
}