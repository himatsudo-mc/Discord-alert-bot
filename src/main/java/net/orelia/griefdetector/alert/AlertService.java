package net.orelia.griefdetector.alert;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * アラートの集約点。クールダウン判定 → コンソール/ゲーム内通知 → Discord 非同期送信を行う。
 * どのスレッドから呼んでもよい（通知系はメインスレッドへ、HTTP は専用スレッドへディスパッチする）。
 */
public final class AlertService {

    private final Plugin plugin;
    private final DiscordWebhookClient webhookClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GriefDetector-Discord");
        t.setDaemon(true);
        return t;
    });

    /** (プレイヤーUUID + 種別) → 最終通知時刻(ミリ秒)。通知スパム防止用。 */
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    private volatile String webhookUrl = "";
    private volatile String username = "GriefDetector";
    private volatile long cooldownMillis = 60_000L;
    private volatile boolean inGameNotify = true;

    public AlertService(Plugin plugin) {
        this.plugin = plugin;
        this.webhookClient = new DiscordWebhookClient(plugin.getLogger());
    }

    /** config.yml の値を反映する（起動時・/griefdetect reload 時）。 */
    public void configure(String webhookUrl, String username, int cooldownSeconds, boolean inGameNotify) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username == null || username.isBlank() ? "GriefDetector" : username;
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
        this.inGameNotify = inGameNotify;
        if (this.webhookUrl.isEmpty()) {
            plugin.getLogger().warning("discord.webhook-url が未設定です。Discord 通知はスキップされます。");
        }
    }

    /**
     * アラートを発報する。クールダウン中の同一プレイヤー・同一種別は破棄する。
     * @return 実際に通知された場合 true
     */
    public boolean raise(Alert alert) {
        if (!allowedByCooldown(alert.playerId(), alert.type())) {
            return false;
        }
        plugin.getLogger().warning(String.format(
                "[荒らし検知] 種別=%s プレイヤー=%s(%s) 座標=%s IP=%s 詳細=%s",
                alert.type().label(), alert.playerName(), alert.playerId(),
                alert.coordinates(), alert.ipAddress(), alert.detail()));

        if (inGameNotify) {
            String message = "§c[GriefDetector] §f" + alert.type().label() + ": §e" + alert.playerName()
                    + " §7@ " + alert.coordinates() + " §f- " + alert.detail();
            runOnMainThread(() -> Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("griefdetector.notify"))
                    .forEach(p -> p.sendMessage(message)));
        }

        String url = webhookUrl;
        if (!url.isEmpty()) {
            executor.execute(() -> webhookClient.send(url, username, alert));
        }
        return true;
    }

    private boolean allowedByCooldown(UUID playerId, AlertType type) {
        long now = System.currentTimeMillis();
        String key = playerId + ":" + type.name();
        Long previous = lastAlertAt.get(key);
        if (previous != null && now - previous < cooldownMillis) {
            return false;
        }
        lastAlertAt.put(key, now);
        return true;
    }

    private void runOnMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** プラグイン無効化時に呼ぶ。送信中のアラートを少しだけ待って停止する。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
