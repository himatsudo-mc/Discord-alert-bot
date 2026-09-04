package net.orelia.griefdetector.alert;

/** 荒らし種別。 */
public enum AlertType {
    TNT("TNT大量着火", Severity.ALERT),
    SPEED("異常移動速度", Severity.ALERT),
    NUKER("Nuker（高速ブロック破壊）", Severity.ALERT),
    FLY("不正飛行（エリトラ以外）", Severity.ALERT),
    ARSON("放火・延焼", Severity.ALERT),
    COMBAT("近接戦闘系（Reach/Killaura/オートクリッカー）", Severity.ALERT),
    /** 統計的異常検知であり誤検知（偶然の可能性）を含むため常に warning 扱い。 */
    XRAY("Xray疑惑（統計的異常）", Severity.WARNING);

    private final String label;
    private final Severity severity;

    AlertType(String label, Severity severity) {
        this.label = label;
        this.severity = severity;
    }

    /** Discord/ゲーム内通知に表示する日本語ラベル。 */
    public String label() {
        return label;
    }

    /** アラートの重要度（Discord embed の色分けに使う）。 */
    public Severity severity() {
        return severity;
    }
}
