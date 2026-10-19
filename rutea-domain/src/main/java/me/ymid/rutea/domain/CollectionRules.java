package me.ymid.rutea.domain;

import java.time.LocalTime;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Reglas de recolección del Decreto 1077 de 2015 (Libro 2, Parte 3, Título 2, que compila el
 * Decreto 2981 de 2013) que el planificador debe respetar. Solo están las que ya se usan;
 * el resto de {@code docs/normativa.md} entra en la fase que las necesita.
 */
public final class CollectionRules {

    /**
     * R02: entre las 21:00 y las 06:00 del día siguiente, la recolección en zonas residenciales,
     * hoteles, hospitales, clínicas y demás centros asistenciales exige medidas especiales para
     * mitigar el ruido de la recolección y la compactación.
     */
    public static final NormRef NIGHT_NOISE = new NormRef("R02", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.31, parágrafo", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /** R03: la recolección y el transporte de no aprovechables se hacen al menos dos veces por semana. */
    public static final NormRef MINIMUM_FREQUENCY = new NormRef("R03", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.32, parágrafo", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /** Franja de R02. Cruza la medianoche. */
    public static final NightWindow NIGHT_WINDOW = new NightWindow(LocalTime.of(21, 0), LocalTime.of(6, 0));

    static final int MINIMUM_WEEKLY_NON_RECYCLABLE = 2;

    private CollectionRules() {
    }

    /**
     * Frecuencia mínima semanal que la norma fija para la corriente (R03). Solo la hay para no
     * aprovechables; para las demás la frecuencia depende de la generación y del PGIRS.
     */
    public static OptionalInt minimumWeeklyFrequency(WasteStream stream) {
        Objects.requireNonNull(stream, "stream");
        return stream == WasteStream.NON_RECYCLABLE
                ? OptionalInt.of(MINIMUM_WEEKLY_NON_RECYCLABLE)
                : OptionalInt.empty();
    }

    /**
     * Franja horaria que puede cruzar la medianoche.
     *
     * <p>Incluye ambos extremos: la norma dice "entre las 21:00 y las 06:00", y ante la duda
     * Rutea marca la parada en lugar de omitir una medida de mitigación.
     */
    public record NightWindow(LocalTime start, LocalTime end) {

        public NightWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
        }

        public boolean contains(LocalTime time) {
            Objects.requireNonNull(time, "time");
            if (start.isAfter(end)) {
                return !time.isBefore(start) || !time.isAfter(end);
            }
            return !time.isBefore(start) && !time.isAfter(end);
        }
    }
}
