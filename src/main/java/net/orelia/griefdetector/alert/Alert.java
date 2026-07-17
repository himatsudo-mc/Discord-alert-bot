package net.orelia.griefdetector.alert;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** 1件のアラート。通知必須5項目（検知時間/プレイヤーID/座標/IP/種別）+ 詳細を保持する。 */
public record Alert(
        Instant time,
        String playerName,
        UUID playerId,
        String world,
        int x,
        int y,
        int z,
        String ipAddress,
        AlertType type,
        String detail) {

    /** メインスレッド上でプレイヤーの現在状態からアラートを組み立てる。 */
    public static Alert of(Player player, AlertType type, String detail) {
        Location loc = player.getLocation();
        String ip = "不明";
        if (player.getAddress() != null && player.getAddress().getAddress() != null) {
            ip = player.getAddress().getAddress().getHostAddress();
        }
        return new Alert(
                Instant.now(),
                player.getName(),
                player.getUniqueId(),
                loc.getWorld() != null ? loc.getWorld().getName() : "不明",
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ(),
                ip,
                type,
                detail);
    }

    public String coordinates() {
        return world + " (" + x + ", " + y + ", " + z + ")";
    }
}
