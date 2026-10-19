package me.ymid.rutea.domain;

/**
 * Corrientes de residuo según el código de colores para la separación en la fuente
 * (Resolución 2184 de 2019, art. 4). Cada corriente se recoge en rutas separadas.
 */
public enum WasteStream {

    /** Plástico, vidrio, metales, multicapa, papel y cartón. */
    RECYCLABLE("Residuos aprovechables", BinColor.WHITE),

    ORGANIC("Residuos orgánicos aprovechables", BinColor.GREEN),

    NON_RECYCLABLE("Residuos no aprovechables", BinColor.BLACK);

    /** R09: código de colores, obligatorio para municipios y distritos desde el 1 de enero de 2021. */
    public static final NormRef COLOR_CODE = new NormRef("R09", Norms.RESOLUTION_2184_2019, "art. 4",
            Norms.RESOLUTION_2184_2019_SOURCE, Verification.VERIFICADA);

    private final String officialName;
    private final BinColor color;

    WasteStream(String officialName, BinColor color) {
        this.officialName = officialName;
        this.color = color;
    }

    /** Nombre de la corriente tal como la nombra la norma. */
    public String officialName() {
        return officialName;
    }

    /** Color oficial de la bolsa o recipiente. */
    public BinColor color() {
        return color;
    }

    /** Colores del código oficial. La adaptación para el mapa oscuro vive en el frontend. */
    public enum BinColor {
        WHITE("blanco"),
        GREEN("verde"),
        BLACK("negro");

        private final String officialName;

        BinColor(String officialName) {
            this.officialName = officialName;
        }

        public String officialName() {
            return officialName;
        }
    }
}
