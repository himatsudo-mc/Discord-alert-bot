package net.orelia.griefdetector.detect;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import net.orelia.griefdetector.grim.GrimBridge;

/**
 * 全 Detector のライフサイクル管理。
 * config.yml のマスタースイッチと detectors.<name>.enabled を読み、
 * 有効なモジュールだけを起動する（無効モジュールはリスナー登録すら行わない）。
 */
public final class DetectorManager {

    private final Plugin plugin;
    private final GrimBridge grimBridge;
    private final Map<String, Detector> detectors = new LinkedHashMap<>();
    private final Map<String, Boolean> lastStartResult = new LinkedHashMap<>();
    private boolean masterEnabled;

    public DetectorManager(Plugin plugin, GrimBridge grimBridge) {
        this.plugin = plugin;
        this.grimBridge = grimBridge;
    }

    /** 新しい検知モジュールはここで register するだけで組み込まれる。 */
    public void register(Detector detector) {
        detectors.put(detector.name(), detector);
    }

    /** 設定を読み直し、全モジュールを再起動する（初回起動と /griefdetect reload の両方で使う）。 */
    public void reload(FileConfiguration config) {
        stopAll();
        masterEnabled = config.getBoolean("enabled", true);
        if (!masterEnabled) {
            plugin.getLogger().warning("enabled: false のため、全検知モジュールを停止しています。");
            return;
        }

        grimBridge.start(config.getConfigurationSection("grim"));

        ConfigurationSection detectorsSection = config.getConfigurationSection("detectors");
        for (Detector detector : detectors.values()) {
            ConfigurationSection section = detectorsSection != null
                    ? detectorsSection.getConfigurationSection(detector.name())
                    : null;
            if (section == null) {
                section = new MemoryConfiguration();
            }
            if (!section.getBoolean("enabled", true)) {
                plugin.getLogger().info("検知モジュール [" + detector.name() + "] は無効化されています。");
                lastStartResult.put(detector.name(), false);
                continue;
            }
            boolean started = detector.start(section);
            lastStartResult.put(detector.name(), started);
            plugin.getLogger().info("検知モジュール [" + detector.name() + "] "
                    + (started ? "を起動しました: " + detector.statusLine() : "は起動されませんでした。"));
        }
    }

    public void stopAll() {
        for (Detector detector : detectors.values()) {
            detector.stop();
        }
        grimBridge.stop();
        lastStartResult.clear();
    }

    /** /griefdetect status 用のサマリ。 */
    public Map<String, String> statusLines() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("master", masterEnabled ? "有効" : "無効（全停止中）");
        lines.put("grim", grimBridge.statusLine());
        for (Detector detector : detectors.values()) {
            lines.put(detector.name(), detector.statusLine());
        }
        return lines;
    }
}
