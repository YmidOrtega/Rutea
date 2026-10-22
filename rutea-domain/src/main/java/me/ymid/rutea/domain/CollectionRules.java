package me.ymid.rutea.domain;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;
import java.util.OptionalInt;

public final class CollectionRules {

    public static final NormRef NIGHT_NOISE = new NormRef("R02", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.31, parágrafo", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    public static final NormRef MINIMUM_FREQUENCY = new NormRef("R03", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.32, parágrafo", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /**
     * La compactación se hace donde cause la mínima molestia y nunca frente a centros educativos, hospitales,
     * clínicas o centros asistenciales.
     */
    public static final NormRef NO_COMPACTION = new NormRef("R07", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.27, num. 5", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /**
     * Con más de 5.000 usuarios, los vehículos tienen equipo de compactación, excepto los de residuos separados
     * para aprovechamiento.
     */
    public static final NormRef COMPACTION_EQUIPMENT = new NormRef("R06", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.36, num. 3", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /**
     * El aprovechamiento es exclusivo de las organizaciones de recicladores de oficio e incluye el transporte
     * selectivo hasta una Estación de Clasificación y Aprovechamiento (ECA).
     */
    public static final NormRef SELECTIVE_TO_ECA = new NormRef("R10", Norms.DECREE_1381_2024,
            "art. 2.3.2.5.1.1, par. 1 y art. 2.3.2.5.2.6, num. 4", Norms.DECREE_1381_2024_SOURCE,
            Verification.VERIFICADA);

    /**
     * Frecuencias, rutas y horarios se divulgan: la página web del prestador publica rutas y horarios, y las facturas
     * informan las frecuencias.
     */
    public static final NormRef SCHEDULE_DISCLOSURE = new NormRef("R04", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.33", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    /** Ante la avería de un vehículo, el servicio se restablece en máximo tres horas. */
    public static final NormRef BREAKDOWN_RECOVERY = new NormRef("R05", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.34", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    public static final Duration MAX_BREAKDOWN_RECOVERY = Duration.ofHours(3);

    /** Todo cambio de rutas, horarios o frecuencias se avisa con tres días, salvo caso fortuito o fuerza mayor. */
    public static final NormRef ROUTE_CHANGE_NOTICE = new NormRef("R05", Norms.DECREE_1077_2015,
            "art. 2.3.2.2.2.3.34", Norms.DECREE_1077_2015_SOURCE, Verification.VERIFICADA);

    public static final Duration MIN_ROUTE_CHANGE_NOTICE = Duration.ofDays(3);

    public static final NightWindow NIGHT_WINDOW = new NightWindow(LocalTime.of(21, 0), LocalTime.of(6, 0));

    static final int MINIMUM_WEEKLY_NON_RECYCLABLE = 2;

    private CollectionRules() {
    }

    public static OptionalInt minimumWeeklyFrequency(WasteStream stream) {
        Objects.requireNonNull(stream, "stream");
        return stream == WasteStream.NON_RECYCLABLE
                ? OptionalInt.of(MINIMUM_WEEKLY_NON_RECYCLABLE)
                : OptionalInt.empty();
    }

    /**
     * Incluye ambos extremos: la norma dice "entre las 21:00 y las 06:00" y, ante la duda, es mejor
     * marcar la parada que omitir una medida de mitigación.
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
