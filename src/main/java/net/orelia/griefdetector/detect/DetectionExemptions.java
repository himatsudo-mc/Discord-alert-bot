package net.orelia.griefdetector.detect;

import org.bukkit.entity.Player;

/**
 * 検知対象から除外するプレイヤーの共通判定。
 * OP はサーバー管理作業（ワールド編集・テレポート・高速建築コマンド等）で
 * 通常のプレイヤーには不可能な挙動を頻繁に行うため、全検知モジュールで
 * 一律に対象外とする。griefdetector.bypass 権限（default: op）を持つ
 * 非OPスタッフも同様に除外できる。
 */
public final class DetectionExemptions {

    public static final String BYPASS_PERMISSION = "griefdetector.bypass";

    private DetectionExemptions() {
    }

    public static boolean isBypassed(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }
}
