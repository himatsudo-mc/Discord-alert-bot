package net.orelia.griefdetector;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import net.orelia.griefdetector.alert.AlertService;
import net.orelia.griefdetector.command.GriefDetectCommand;
import net.orelia.griefdetector.detect.DetectorManager;
import net.orelia.griefdetector.detect.NukerDetector;
import net.orelia.griefdetector.detect.SpeedDetector;
import net.orelia.griefdetector.detect.TntDetector;
import net.orelia.griefdetector.grim.GrimBridge;

/**
 * GriefDetector 本体。
 * 役割は「異常を検知し、即座に Discord へ通知する」ことのみに絞る。
 * 防御は WorldGuard/Grim、復旧は CoreProtect/バックアップが担う前提。
 */
public final class GriefDetectorPlugin extends JavaPlugin {

    private AlertService alertService;
    private GrimBridge grimBridge;
    private DetectorManager detectorManager;

    @Override
    public void onEnable() {
        // 初回起動時に plugins/GriefDetector/config.yml を自動生成する
        saveDefaultConfig();

        alertService = new AlertService(this);
        grimBridge = new GrimBridge(this, alertService);
        detectorManager = new DetectorManager(this, grimBridge);

        detectorManager.register(new TntDetector(this, alertService));
        detectorManager.register(new SpeedDetector(this, alertService, grimBridge));
        detectorManager.register(new NukerDetector(this, alertService, grimBridge));

        reloadAll();

        PluginCommand command = getCommand("griefdetect");
        if (command != null) {
            GriefDetectCommand executor = new GriefDetectCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getLogger().info("GriefDetector を有効化しました。");
    }

    @Override
    public void onDisable() {
        if (detectorManager != null) {
            detectorManager.stopAll();
        }
        if (alertService != null) {
            alertService.shutdown();
        }
        getLogger().info("GriefDetector を無効化しました。");
    }

    /** 設定の再読み込みと全モジュールの再起動（起動時・/griefdetect reload）。 */
    public void reloadAll() {
        reloadConfig();
        alertService.configure(
                getConfig().getString("discord.webhook-url", ""),
                getConfig().getString("discord.username", "GriefDetector"),
                getConfig().getInt("discord.cooldown-seconds", 60),
                getConfig().getBoolean("in-game-notify", true));
        detectorManager.reload(getConfig());
    }

    public AlertService getAlertService() {
        return alertService;
    }

    public DetectorManager getDetectorManager() {
        return detectorManager;
    }
}
