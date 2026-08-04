package net.orelia.griefdetector.detect;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.plugin.Plugin;

import net.orelia.griefdetector.alert.Alert;
import net.orelia.griefdetector.alert.AlertService;
import net.orelia.griefdetector.alert.AlertType;

/**
 * TNT検知（完全自前実装）。
 * プレイヤーに起因する TNT 着火をスライディングウィンドウで数え、閾値超過でアラートを出す。
 */
public final class TntDetector implements Detector, Listener {

    private final Plugin plugin;
    private final AlertService alerts;

    private final Map<UUID, Deque<Long>> ignitionTimes = new ConcurrentHashMap<>();

    private boolean running;
    private long windowMillis = 60_000L;
    private int maxIgnitions = 5;

    public TntDetector(Plugin plugin, AlertService alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    @Override
    public String name() {
        return "tnt";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        windowMillis = Math.max(1, section.getInt("window-seconds", 60)) * 1000L;
        maxIgnitions = Math.max(1, section.getInt("max-ignitions", 5));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        running = true;
        return true;
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        ignitionTimes.clear();
        running = false;
    }

    @Override
    public String statusLine() {
        return running
                ? "稼働中（" + (windowMillis / 1000) + "秒間に " + maxIgnitions + " 回超の着火でアラート）"
                : "停止";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTntPrime(TNTPrimeEvent event) {
        Player igniter = resolvePlayer(event.getPrimingEntity());
        if (igniter == null || DetectionExemptions.isBypassed(igniter)) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = ignitionTimes.computeIfAbsent(igniter.getUniqueId(), k -> new ArrayDeque<>());
        int count;
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            count = times.size();
        }
        if (count > maxIgnitions) {
            boolean notified = alerts.raise(Alert.of(igniter, AlertType.TNT,
                    String.format("%d秒間に %d 回の TNT 着火（閾値: %d 回）",
                            windowMillis / 1000, count, maxIgnitions)));
            if (notified) {
                synchronized (times) {
                    times.clear();
                }
            }
        }
    }

    /** 着火エンティティからプレイヤーを特定する（火矢などの飛翔体経由も辿る）。 */
    private static Player resolvePlayer(Entity primer) {
        if (primer instanceof Player player) {
            return player;
        }
        if (primer instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
