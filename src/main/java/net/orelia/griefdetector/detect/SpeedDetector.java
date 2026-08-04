package net.orelia.griefdetector.detect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.orelia.griefdetector.alert.Alert;
import net.orelia.griefdetector.alert.AlertService;
import net.orelia.griefdetector.alert.AlertType;
import net.orelia.griefdetector.grim.GrimBridge;

/**
 * 異常移動速度検知。
 *
 * Tier A: GrimAC が利用可能なら GrimBridge に SPEED 種別の監視を委譲する（高精度・推奨）。
 * Tier B: 自前ロジック。1秒ごとに水平移動距離を集計し、正当な高速化要因
 * （エリトラ滑空・俊敏効果・騎乗・飛行・トライデント等）を除外した上で、
 * 規定速度を連続超過した場合にアラートを出す。
 * ※Tier B は簡易判定であり、極端なラグ等で誤検知しうる補助的検知。
 */
public final class SpeedDetector implements Detector, Listener {

    private final Plugin plugin;
    private final AlertService alerts;
    private final GrimBridge grim;

    /** プレイヤーごとの1秒間の水平移動距離アキュムレータ。 */
    private final Map<UUID, Double> distanceThisSecond = new ConcurrentHashMap<>();
    /** 連続超過秒数。 */
    private final Map<UUID, Integer> sustainedSeconds = new ConcurrentHashMap<>();
    /** 直近のテレポート時刻（直後の移動は判定除外）。 */
    private final Map<UUID, Long> lastTeleportAt = new ConcurrentHashMap<>();

    private DetectionMode mode = DetectionMode.AUTO;
    private boolean customRunning;
    private boolean grimDelegated;
    private double maxBlocksPerSecond = 12.0;
    private int requiredSustainedSeconds = 3;
    private BukkitTask task;

    public SpeedDetector(Plugin plugin, AlertService alerts, GrimBridge grim) {
        this.plugin = plugin;
        this.alerts = alerts;
        this.grim = grim;
    }

    @Override
    public String name() {
        return "speed";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        mode = DetectionMode.parse(section.getString("mode", "auto"));
        maxBlocksPerSecond = Math.max(0.1, section.getDouble("max-horizontal-blocks-per-second", 12.0));
        requiredSustainedSeconds = Math.max(1, section.getInt("sustained-seconds", 3));

        switch (mode) {
            case OFF -> {
                return false;
            }
            case GRIM -> {
                if (!grim.isActive()) {
                    plugin.getLogger().warning("speed.mode: grim ですが GrimAC にフックできていないため、Speed検知は動作しません。");
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
        grim.watch(AlertType.SPEED);
        grimDelegated = true;
    }

    private void startCustom() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 1秒（20tick）ごとに各プレイヤーの移動距離を評価する
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::evaluateSecond, 20L, 20L);
        customRunning = true;
    }

    @Override
    public void stop() {
        if (grimDelegated) {
            grim.unwatch(AlertType.SPEED);
            grimDelegated = false;
        }
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        distanceThisSecond.clear();
        sustainedSeconds.clear();
        lastTeleportAt.clear();
        customRunning = false;
    }

    @Override
    public String statusLine() {
        if (grimDelegated) {
            return "稼働中（GrimAC 委譲 / Tier A）";
        }
        if (customRunning) {
            return String.format("稼働中（自前ロジック / Tier B: %.1f b/s 超を %d 秒連続でアラート）",
                    maxBlocksPerSecond, requiredSustainedSeconds);
        }
        return "停止";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // 1イベントで8ブロック超はテレポート系とみなして無視
        if (horizontal > 8.0) {
            return;
        }
        distanceThisSecond.merge(event.getPlayer().getUniqueId(), horizontal, Double::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastTeleportAt.put(id, System.currentTimeMillis());
        distanceThisSecond.remove(id);
        sustainedSeconds.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        distanceThisSecond.remove(id);
        sustainedSeconds.remove(id);
        lastTeleportAt.remove(id);
    }

    private void evaluateSecond() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            double distance = distanceThisSecond.getOrDefault(id, 0.0);
            distanceThisSecond.remove(id);

            if (distance <= maxBlocksPerSecond || isExempt(player)) {
                sustainedSeconds.remove(id);
                continue;
            }
            int sustained = sustainedSeconds.merge(id, 1, Integer::sum);
            if (sustained >= requiredSustainedSeconds) {
                sustainedSeconds.remove(id);
                alerts.raise(Alert.of(player, AlertType.SPEED,
                        String.format("水平移動速度 %.1f b/s を %d 秒連続で超過（閾値: %.1f b/s）※簡易判定(Tier B)",
                                distance, sustained, maxBlocksPerSecond)));
            }
        }
        sustainedSeconds.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    /** 正当に高速移動しうる状態は判定から除外する。 */
    private boolean isExempt(Player player) {
        if (DetectionExemptions.isBypassed(player)) {
            return true;
        }
        if (player.isGliding() || player.isRiptiding() || player.isFlying() || player.getAllowFlight()) {
            return true;
        }
        if (player.isInsideVehicle()) {
            return true;
        }
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (player.hasPotionEffect(PotionEffectType.SPEED)
                || player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            return true;
        }
        Long teleport = lastTeleportAt.get(player.getUniqueId());
        return teleport != null && System.currentTimeMillis() - teleport < 3000L;
    }
}
