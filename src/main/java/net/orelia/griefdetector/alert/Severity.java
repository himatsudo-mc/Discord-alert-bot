package net.orelia.griefdetector.alert;

/** アラートの重要度。Discord embed の色・アイコンを切り替えるために使う。 */
public enum Severity {
    /** 確度の高い荒らし行為。Discord embed は赤。 */
    ALERT(15548997, "🚨"),
    /** 統計的異常など参考情報（誤検知の可能性あり）。Discord embed は橙。 */
    WARNING(15105570, "⚠️");

    private final int color;
    private final String icon;

    Severity(int color, String icon) {
        this.color = color;
        this.icon = icon;
    }

    public int color() {
        return color;
    }

    public String icon() {
        return icon;
    }
}
