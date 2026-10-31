package me.ymid.rutea.app.application;

import java.util.Objects;

import me.ymid.rutea.app.scenario.DoorToDoor;
import me.ymid.rutea.app.scenario.ServiceAreas;
import me.ymid.rutea.app.scenario.ServiceAreas.Locality;

/** Una operación en vivo: una localidad urbana en una franja del puerta a puerta. */
public record LiveKey(Locality locality, DoorToDoor.Slot slot) {

    public LiveKey {
        Objects.requireNonNull(locality, "locality");
        Objects.requireNonNull(slot, "slot");
    }

    /** @throws IllegalArgumentException si la localidad no existe o es rural, o la franja no es una de las cuatro */
    public static LiveKey of(int locality, String slot) {
        Locality chosen = ServiceAreas.locality(locality).orElseThrow(() -> new IllegalArgumentException(
                "no hay localidad " + locality + "; van de 1 (Usaquén) a 20 (Sumapaz)"));
        if (chosen.rural()) {
            throw new IllegalArgumentException(chosen.name() + " es rural: no tiene puerta a puerta");
        }
        return new LiveKey(chosen, DoorToDoor.Slot.ALL.stream().filter(candidate -> candidate.code().equals(slot))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no hay franja " + slot + "; son "
                        + String.join(", ", DoorToDoor.Slot.ALL.stream().map(DoorToDoor.Slot::code).toList()))));
    }

    /** Como va en la URL y en el registro de turnos: {@code 11-LMVNoche}. */
    String code() {
        return locality.number() + "-" + slot.code();
    }
}
