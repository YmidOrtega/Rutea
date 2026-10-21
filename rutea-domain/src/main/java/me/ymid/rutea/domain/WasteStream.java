package me.ymid.rutea.domain;

public enum WasteStream {

    RECYCLABLE("Residuos aprovechables", BinColor.WHITE),

    ORGANIC("Residuos orgánicos aprovechables", BinColor.GREEN),

    NON_RECYCLABLE("Residuos no aprovechables", BinColor.BLACK);

    public static final NormRef COLOR_CODE = new NormRef("R09", Norms.RESOLUTION_2184_2019, "art. 4",
            Norms.RESOLUTION_2184_2019_SOURCE, Verification.VERIFICADA);

    private final String officialName;
    private final BinColor color;

    WasteStream(String officialName, BinColor color) {
        this.officialName = officialName;
        this.color = color;
    }

    public String officialName() {
        return officialName;
    }

    public BinColor color() {
        return color;
    }

    /** La adaptación de estos colores al mapa oscuro vive en el frontend. */
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
