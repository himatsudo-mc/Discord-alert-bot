package net.orelia.griefdetector.alert;

/** 荒らし種別。 */
public enum AlertType {
    TNT("TNT大量着火"),
    SPEED("異常移動速度"),
    NUKER("Nuker（高速ブロック破壊）");

    private final String label;

    AlertType(String label) {
        this.label = label;
    }

    /** Discord/ゲーム内通知に表示する日本語ラベル。 */
    public String label() {
        return label;
    }
}
