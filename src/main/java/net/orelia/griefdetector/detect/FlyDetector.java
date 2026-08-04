package net.orelia.griefdetector.detect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
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
 * 飛行検知（エリトラ以外の不正飛行・FlyHack系）。
 *
 * Tier A: GrimAC が利用可能なら GrimBridge に FLY 種別の監視を委譲する（高精度・推奨）。
 * Tier B: 自前ロジック。「接地せず・落下もせず、空中に静止または上昇し続けている」
 * 状態が規定秒数続いた場合にアラートを出す。エリトラ滑空・浮遊/低速落下効果・水中・
 * はしご・騎乗・飛行許可（クリエイティブ等）は除外する。
 * ※Tier B は簡易判定であり、足場のすり抜けバグ等で誤検知しうる補助的検知。
 */
public final class FlyDetector implements Detector, Listener {

    private final Plugin plugin;
    private final AlertService alerts;
    private final GrimBridge grim;

    /** 連続滞空秒数。 */
    private final Map<UUID, Integer> airborneSeconds = new ConcurrentHashMap<>();
    /** 前回評価時の Y 座標（落下判定用）。 */
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    /** 直近のテレポート時刻（直後は判定除外）。 */
    private final Map<UUID, Long> lastTeleportAt = new ConcurrentHashMap<>();

    private DetectionMode mode = DetectionMode.AUTO;
    private boolean customRunning;
    private boolean grimDelegated;
    private int maxAirborneSeconds = 5;
    private BukkitTask task;

    public FlyDetector(Plugin plugin, AlertService alerts, GrimBridge grim) {
        this.plugin = plugin;
        this.alerts = alerts;
        this.grim = grim;
    }

    @Override
    public String name() {
        return "fly";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        mode = DetectionMode.parse(section.getString("mode", "auto"));
        maxAirborneSeconds = Math.max(2, section.getInt("max-airborne-seconds", 5));

        switch (mode) {
            case OFF -> {
                return false;
            }
            case GRIM -> {
                if (!grim.isActive()) {
                    plugin.getLogger().warning("fly.mode: grim ですが GrimAC にフックできていないため、飛行検知は動作しません。");
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
        grim.watch(AlertType.FLY);
        grimDelegated = true;
    }

    private void startCustom() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // 1秒（20tick）ごとに各プレイヤーの滞空状態を評価する
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::evaluateSecond, 20L, 20L);
        customRunning = true;
    }

    @Override
    public void stop() {
        if (grimDelegated) {
            grim.unwatch(AlertType.FLY);
            grimDelegated = false;
        }
        HandlerList.unregisterAll(this);
        if (task != null) {
            task.cancel();
            task = null;
        }
        airborneSeconds.clear();
        lastY.clear();
        lastTeleportAt.clear();
        customRunning = false;
    }

    @Override
    public String statusLine() {
        if (grimDelegated) {
            return "稼働中（GrimAC 委譲 / Tier A）";
        }
        if (customRunning) {
            return String.format("稼働中（自前ロジック / Tier B: 落下なしの滞空 %d 秒でアラート）",
                    maxAirborneSeconds);
        }
        return "停止";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastTeleportAt.put(id, System.currentTimeMillis());
        airborneSeconds.remove(id);
        lastY.remove(id);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        airborneSeconds.remove(id);
        lastY.remove(id);
        lastTeleportAt.remove(id);
    }

    private void evaluateSecond() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            double y = player.getLocation().getY();
            Double previousY = lastY.put(id, y);

            if (previousY == null || player.isOnGround() || isExempt(player)) {
                airborneSeconds.remove(id);
                continue;
            }
            // 1秒で 0.2 ブロック以上下降していれば通常の落下とみなす
            if (y <= previousY - 0.2) {
                airborneSeconds.remove(id);
                continue;
            }
            int sustained = airborneSeconds.merge(id, 1, Integer::sum);
            if (sustained >= maxAirborneSeconds) {
                airborneSeconds.remove(id);
                alerts.raise(Alert.of(player, AlertType.FLY,
                        String.format("接地・落下なしで %d 秒間滞空（閾値: %d 秒）※簡易判定(Tier B)",
                                sustained, maxAirborneSeconds)));
            }
        }
        airborneSeconds.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        lastY.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    /** 正当に滞空しうる状態は判定から除外する。 */
    private boolean isExempt(Player player) {
        if (DetectionExemptions.isBypassed(player)) {
            return true;
        }
        if (player.isGliding() || player.isRiptiding() || player.isFlying() || player.getAllowFlight()) {
            return true;
        }
        if (player.isInsideVehicle() || player.isInWater() || player.isClimbing()) {
            return true;
        }
        GameMode gm = player.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) {
            return true;
        }
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            return true;
        }
        Long teleport = lastTeleportAt.get(player.getUniqueId());
        return teleport != null && System.currentTimeMillis() - teleport < 3000L;
    }
}
