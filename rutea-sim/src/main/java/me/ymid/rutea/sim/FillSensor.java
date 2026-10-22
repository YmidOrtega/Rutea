package me.ymid.rutea.sim;

/**
 * El sensor de llenado de un contenedor: cuánto entra por segundo y cuánto cabe, en kilos (los litros del contenedor
 * por la densidad de los residuos sueltos). La generación se supone pareja a lo largo del día.
 */
public record FillSensor(double capacityKg, double kgPerSecond) {

    public FillSensor {
        if (!(capacityKg > 0) || !(kgPerSecond > 0)) {
            throw new IllegalArgumentException("capacidad y generación deben ser positivas");
        }
    }

    public static FillSensor of(double liters, double densityKgPerM3, double kgPerDay) {
        return new FillSensor(liters / 1000 * densityKgPerM3, kgPerDay / 86_400);
    }

    /** Fracción llena tras {@code seconds} desde que se vació; más de 1 es un desborde. */
    public double fill(double seconds) {
        return Math.max(0, seconds) * kgPerSecond / capacityKg;
    }

    public double secondsToFull() {
        return capacityKg / kgPerSecond;
    }

    /** La lectura que transmite: el porcentaje en pasos, como los sensores reales, que no reportan cada kilo. */
    public static int reading(double fill, int stepPercent) {
        return (int) Math.floor(fill * 100 / stepPercent) * stepPercent;
    }
}
