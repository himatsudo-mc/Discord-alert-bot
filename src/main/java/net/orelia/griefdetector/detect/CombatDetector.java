package net.orelia.griefdetector.detect;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import net.orelia.griefdetector.alert.AlertType;
import net.orelia.griefdetector.grim.GrimBridge;

/**
 * 近接戦闘系検知（Reach/Killaura/オートクリッカー等）。
 *
 * GrimAC 連携（Tier A）専任。自前ロジック（Tier B）は実装しない方針とする
 * （簡易な自前判定は誤検知率が高く、TNTのみ常時自前実装という既存の設計方針とも
 * 整合しないため）。mode: custom が指定された場合は警告ログを出し、起動しない。
 */
public final class CombatDetector implements Detector {

    private final Plugin plugin;
    private final GrimBridge grim;

    private DetectionMode mode = DetectionMode.AUTO;
    private boolean grimDelegated;

    public CombatDetector(Plugin plugin, GrimBridge grim) {
        this.plugin = plugin;
        this.grim = grim;
    }

    @Override
    public String name() {
        return "combat";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        mode = DetectionMode.parse(section.getString("mode", "auto"));

        switch (mode) {
            case OFF -> {
                return false;
            }
            case CUSTOM -> {
                plugin.getLogger().warning(
                        "combat.mode: custom は未対応です（近接戦闘系の自前ロジックは実装していません）。"
                                + " GrimAC 連携（auto/grim）を使用してください。");
                return false;
            }
            case GRIM -> {
                if (!grim.isActive()) {
                    plugin.getLogger().warning("combat.mode: grim ですが GrimAC にフックできていないため、近接戦闘系検知は動作しません。");
                    return false;
                }
                delegateToGrim();
                return true;
            }
            case AUTO -> {
                if (grim.isActive()) {
                    delegateToGrim();
                    return true;
                }
                plugin.getLogger().info("GrimAC が見つからないため、近接戦闘系検知は起動しません（自前ロジックなし）。");
                return false;
            }
        }
        return false;
    }

    private void delegateToGrim() {
        grim.watch(AlertType.COMBAT);
        grimDelegated = true;
    }

    @Override
    public void stop() {
        if (grimDelegated) {
            grim.unwatch(AlertType.COMBAT);
            grimDelegated = false;
        }
    }

    @Override
    public String statusLine() {
        return grimDelegated ? "稼働中（GrimAC 委譲 / Tier A）" : "停止";
    }
}
