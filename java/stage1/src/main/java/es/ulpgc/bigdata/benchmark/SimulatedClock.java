package es.ulpgc.bigdata.benchmark;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Reloj que avanza un paso fijo cada vez que se le pregunta la hora.
 *
 * Para el benchmark de la estructura "time": con el reloj real, los cientos de libros
 * se guardarían en segundos y acabarían todos en la misma carpeta YYYYMMDD/HH.
 * Con este reloj, cada save() cae "un rato después", como si la ingesta durara días,
 * y siempre de la misma forma: el benchmark es reproducible.
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

    /** 10 libros por hora a partir del 1 de enero de 2026: unos 240 libros por carpeta de día. */
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