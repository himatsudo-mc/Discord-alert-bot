package net.orelia.griefdetector.detect;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameMode;
import org.bukkit.Material;
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

/**
 * Xray検知（完全自前実装、GrimAC非依存）。
 *
 * 貴重鉱石の採掘をスライディングウィンドウで数え、閾値超過で warning アラートを出す。
 * 「たまたま高効率で掘り当てた」可能性を否定できない統計的異常検知であり、
 * 常に {@link AlertType#XRAY}（severity: WARNING）として通知される。
 */
public final class XrayDetector implements Detector, Listener {

    private static final List<String> DEFAULT_ORES = List.of(
            "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS",
            "EMERALD_ORE", "DEEPSLATE_EMERALD_ORE");

    private final Plugin plugin;
    private final AlertService alerts;

    private final Map<UUID, Deque<Long>> oreBreakTimes = new ConcurrentHashMap<>();

    private boolean running;
    private long windowMillis = 300_000L;
    private int maxOreBreaks = 8;
    private Set<Material> ores = EnumSet.noneOf(Material.class);

    public XrayDetector(Plugin plugin, AlertService alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    @Override
    public String name() {
        return "xray";
    }

    @Override
    public boolean start(ConfigurationSection section) {
        windowMillis = Math.max(1, section.getInt("window-seconds", 300)) * 1000L;
        maxOreBreaks = Math.max(1, section.getInt("max-ore-breaks", 8));
        ores = parseOres(section.getStringList("ores"));
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        running = true;
        return true;
    }

    @Override
    public void stop() {
        HandlerList.unregisterAll(this);
        oreBreakTimes.clear();
        running = false;
    }

    @Override
    public String statusLine() {
        return running
                ? "稼働中（" + (windowMillis / 1000) + "秒間に貴重鉱石を " + maxOreBreaks + " 個超採掘で warning）"
                : "停止";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!ores.contains(event.getBlock().getType()) || isExempt(player)) {
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> times = oreBreakTimes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        int count;
        synchronized (times) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > windowMillis) {
                times.pollFirst();
            }
            count = times.size();
        }
        if (count > maxOreBreaks) {
            boolean notified = alerts.raise(Alert.of(player, AlertType.XRAY,
                    String.format("%d秒間に貴重鉱石を %d 個採掘（閾値: %d 個）"
                                    + "※統計的異常検知であり誤検知（偶然の可能性）を含む warning",
                            windowMillis / 1000, count, maxOreBreaks)));
            if (notified) {
                synchronized (times) {
                    times.clear();
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        oreBreakTimes.remove(event.getPlayer().getUniqueId());
    }

    /** クリエイティブ/スペクテイターは実採掘とみなさず除外する。 */
    private boolean isExempt(Player player) {
        if (DetectionExemptions.isBypassed(player)) {
            return true;
        }
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    private static Set<Material> parseOres(List<String> configured) {
        List<String> names = configured.isEmpty() ? DEFAULT_ORES : configured;
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            try {
                result.add(Material.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // 不明なマテリアル名は無視する
            }
        }
        return result;
    }
}
