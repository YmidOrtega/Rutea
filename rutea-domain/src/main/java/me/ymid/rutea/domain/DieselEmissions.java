package me.ymid.rutea.domain;

/**
 * CO₂ de quemar diésel (ACPM), con los valores por defecto de las Directrices del IPCC de 2006 para
 * inventarios nacionales de gases de efecto invernadero, volumen 2 (Energía).
 *
 * <p>Todo el diésel se cuenta como fósil. El ACPM colombiano lleva una mezcla de biodiésel cuya parte
 * biogénica se reporta aparte; descontarla queda {@code PENDIENTE_VERIFICAR} (porcentaje de mezcla vigente).
 */
public final class DieselEmissions {

    /** Factor de emisión por defecto del gas/diésel oil en transporte por carretera (cap. 3, Tabla 3.2.1). */
    public static final double KG_CO2_PER_TJ = 74_100;

    /** Poder calorífico neto por defecto del gas/diésel oil (cap. 1, Tabla 1.2). */
    public static final double TJ_PER_GG = 43.0;

    /**
     * Supuesto: densidad típica del diésel, kg/L. No es un dato verificado del ACPM colombiano; lo fija la
     * especificación del combustible.
     */
    public static final double KG_PER_LITER = 0.84;

    private DieselEmissions() {
    }

    /** kg de CO₂ por litro: 0,84 kg × 43,0 TJ/Gg × 74.100 kg/TJ ≈ 2,68. */
    public static double kgCo2PerLiter() {
        return KG_PER_LITER * TJ_PER_GG * 1e-6 * KG_CO2_PER_TJ;
    }

    public static double kgCo2(double liters) {
        if (liters < 0) {
            throw new IllegalArgumentException("litros negativos: " + liters);
        }
        return liters * kgCo2PerLiter();
    }
}
