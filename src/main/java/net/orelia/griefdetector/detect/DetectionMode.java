package net.orelia.griefdetector.detect;

import java.util.Locale;

/** ハイブリッド検知モジュール（Speed/Nuker）の動作モード。 */
public enum DetectionMode {
    /** GrimAC があれば Grim（Tier A）、なければ自前ロジック（Tier B）。 */
    AUTO,
    /** Grim 連携のみ。Grim 未導入なら何もしない。 */
    GRIM,
    /** 常に自前ロジック（誤検知しうる簡易判定）。 */
    CUSTOM,
    /** モジュール無効。 */
    OFF;

    public static DetectionMode parse(String value) {
        if (value == null) {
            return AUTO;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
