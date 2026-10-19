package me.ymid.rutea.domain;

/**
 * Estado de verificación de una regla. Los nombres coinciden con los de {@code docs/normativa.md}
 * para que un mismo grep los encuentre en el código y en la documentación.
 */
public enum Verification {

    /** Contrastada con el texto compilado oficial, incluidas sus notas de vigencia. */
    VERIFICADA,

    /** Tomada de una fuente secundaria; no se presenta como hecho. */
    PENDIENTE_VERIFICAR
}
