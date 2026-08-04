package net.orelia.griefdetector.detect;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import net.orelia.griefdetector.alert.Alert;
import net.orelia.griefdetector.alert.AlertService;
import net.orelia.griefdetector.alert.AlertType;
import net.orelia.griefdetector.grim.GrimBridge;

/**
 * Nuker検知（人間の操作では不可能な速度でのブロック破壊）。
 *
 * Tier A: GrimAC が利用可能なら GrimBridge に NUKER 種別の監視を委譲する。
 * Tier B: 自前ロジック。ブロック破壊をスライディングウィンドウで数え、
 * 閾値超過でアラートを出す（効率エンチャント+加速ビーコン下の正当な高速採掘も
 * 引っかかりうるため、閾値はデフォルトで十分高めに設定してある）。
 */
public final class NukerDetector implements Detector, Listener {

    private final Plugin plugin;
    private final AlertService alerts;
    private final GrimBridge grim;

    private final Map<UUID, Deque<Long>> breakTimes = new ConcurrentHashMap<>();

    private DetectionMode mode = DetectionMode.AUTO;
    private boolean customRunning;
    private boolean grimDelegated;
    private long windowMillis = 2_000L;
    private int maxBreaks = 30;

    public NukerDetector(Plugin plugin, AlertService alerts, GrimBridge grim) {
        this.plugin = plugin;
        this.alerts = alerts;
        this.grim = grim;
    }

    @Override
    public String name() {
        return "nuker";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        mode = DetectionMode.parse(section.getString("mode", "auto"));
        windowMillis = Math.max(1, section.getInt("window-seconds", 2)) * 1000L;
        maxBreaks = Math.max(1, section.getInt("max-breaks", 30));

        switch (mode) {
            case OFF -> {
                return false;
            }
            case GRIM -> {
                if (!grim.isActive()) {
                    plugin.getLogger().warning("nuker.mode: grim ですが GrimAC にフックできていないため、Nuker検知は動作しません。");
                    return false;
                }
                delegateToGrim();
                return true;
            }
            case AUTO -> {
                if (grim.isActive()) {
                    delegateToGrim();
                } else {
                    startCustom();
                }
                return true;
            }
            case CUSTOM -> {
                startCustom();
                return true;
            }
        }
        return false;
    }

    private void delegateToGrim() {
        grim.watch(AlertType.NUKER);
        grimDelegated = true;
    }

    private void startCustom() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        customRunning = true;
    }

    @Override
    public void stop() {
        if (grimDelegated) {
            grim.unwatch(AlertType.NUKER);
            grimDelegated = false;
        }
        HandlerList.unregisterAll(this);
        breakTimes.clear();
        customRunning = false;
    }

    @Override
    public String statusLine() {
        if (grimDelegated) {
            return "稼働中（GrimAC 委譲 / Tier A）";
        }
        if (customRunning) {
            return String.format("稼働中（自前ロジック / Tier B: %d秒間に %d 個超の破壊でアラート）",
                    windowMillis / 1000, maxBreaks);
        }
        return "停止";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (DetectionExemptions.isBypassed(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = breakTimes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        int count;
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            count = times.size();
        }
        if (count > maxBreaks) {
            boolean notified = alerts.raise(Alert.of(player, AlertType.NUKER,
                    String.format("%d秒間に %d 個のブロックを破壊（閾値: %d 個）※簡易判定(Tier B)",
                            windowMillis / 1000, count, maxBreaks)));
            if (notified) {
                synchronized (times) {
                    times.clear();
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        breakTimes.remove(event.getPlayer().getUniqueId());
    }
}
