package net.orelia.griefdetector.grim;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import net.orelia.griefdetector.alert.Alert;
import net.orelia.griefdetector.alert.AlertService;
import net.orelia.griefdetector.alert.AlertType;

/**
 * GrimAC 連携（Tier A）。
 *
 * GrimAC の違反フラグイベント（FlagEvent）をリフレクション経由でフックし、
 * チェック名から荒らし種別（SPEED / NUKER）へ分類して Discord アラートに変換する。
 *
 * 【設計メモ / スパイク結果】
 * Grim のプラグイン間 API はバージョンにより提供状況・パッケージ構成が異なるため、
 * コンパイル時依存（API アーティファクト）は持たず、実行時に候補クラスを探索して
 * 見つかった場合のみ動的にリスナー登録する。見つからなければ Tier B（自前ロジック）に
 * フォールバックする前提のため、フック失敗はエラーではなく情報ログに留める。
 */
public final class GrimBridge implements Listener {

    /** バージョン差を吸収するための FlagEvent 候補クラス名。 */
    private static final String[] FLAG_EVENT_CANDIDATES = {
            "ac.grim.grimac.api.events.FlagEvent",
            "ac.grim.grimac.events.FlagEvent",
    };

    private final Plugin plugin;
    private final AlertService alerts;

    /** 現在 Grim 連携でカバー中の荒らし種別（Speed/Nuker Detector が登録する）。 */
    private final Set<AlertType> activeTypes = ConcurrentHashMap.newKeySet();

    private volatile boolean hooked;
    private volatile List<String> speedChecks = List.of();
    private volatile List<String> nukerChecks = List.of();
    private volatile List<String> flyChecks = List.of();
    private volatile double minViolations = 5;
    private String hookedClassName = "";

    public GrimBridge(Plugin plugin, AlertService alerts) {
        this.plugin = plugin;
        this.alerts = alerts;
    }

    /**
     * Grim へのフックを試みる。
     * @return フックに成功した場合 true（Grim 未導入・API 不在なら false）
     */
    public boolean start(ConfigurationSection grimSection) {
        if (grimSection == null || !grimSection.getBoolean("enabled", true)) {
            return false;
        }
        if (Bukkit.getPluginManager().getPlugin("GrimAC") == null) {
            plugin.getLogger().info("GrimAC が見つかりません。Speed/Nuker は自前ロジック（Tier B）で動作します。");
            return false;
        }
        speedChecks = lowerCase(grimSection.getStringList("speed-checks"));
        nukerChecks = lowerCase(grimSection.getStringList("nuker-checks"));
        flyChecks = lowerCase(grimSection.getStringList("fly-checks"));
        minViolations = grimSection.getDouble("min-violations", 5);

        for (String className : FLAG_EVENT_CANDIDATES) {
            if (tryRegister(className)) {
                hooked = true;
                hookedClassName = className;
                plugin.getLogger().info("GrimAC の " + className + " にフックしました（Tier A 有効）。");
                return true;
            }
        }
        plugin.getLogger().warning(
                "GrimAC は導入されていますが、既知の FlagEvent API が見つかりませんでした。"
                        + " Grim のバージョンを確認してください。Speed/Nuker は自前ロジック（Tier B）で動作します。");
        return false;
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        activeTypes.clear();
        hooked = false;
    }

    public boolean isActive() {
        return hooked;
    }

    /** 指定種別のフラグを Grim 経由でアラート化する（Speed/Nuker Detector が呼ぶ）。 */
    public void watch(AlertType type) {
        activeTypes.add(type);
    }

    public void unwatch(AlertType type) {
        activeTypes.remove(type);
    }

    public String statusLine() {
        return hooked ? "フック済み (" + hookedClassName + ") 監視種別=" + activeTypes : "未接続";
    }

    @SuppressWarnings("unchecked")
    private boolean tryRegister(String className) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className);
        } catch (ClassNotFoundException e) {
            return false;
        }
        if (!Event.class.isAssignableFrom(clazz)) {
            return false;
        }
        Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) clazz,
                this,
                EventPriority.MONITOR,
                (listener, event) -> handleFlag(event),
                plugin,
                false);
        return true;
    }

    /**
     * FlagEvent 受信処理。Grim はイベントを非同期スレッドから発火しうるため、
     * ここではリフレクションで情報を取り出すだけに留め、
     * プレイヤー状態の取得とアラート発報はメインスレッドへ移して行う。
     */
    private void handleFlag(Event event) {
        try {
            Object check = invoke(event, "getCheck");
            String checkName = firstString(
                    check != null ? invoke(check, "getCheckName") : null,
                    invoke(event, "getCheckName"));
            if (checkName == null) {
                return;
            }
            AlertType type = classify(checkName);
            if (type == null || !activeTypes.contains(type)) {
                return;
            }
            double violations = toDouble(check != null ? invoke(check, "getViolations") : null);
            if (violations >= 0 && violations < minViolations) {
                return;
            }

            Object grimUser = invoke(event, "getPlayer");
            UUID uuid = extractUuid(grimUser);
            if (uuid == null) {
                return;
            }
            String detail = String.format("GrimAC チェック「%s」の違反フラグ（VL: %s）",
                    checkName, violations >= 0 ? String.valueOf(violations) : "不明");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    alerts.raise(Alert.of(player, type, detail));
                }
            });
        } catch (Exception e) {
            plugin.getLogger().fine("Grim FlagEvent の解析に失敗: " + e);
        }
    }

    private AlertType classify(String checkName) {
        String lower = checkName.toLowerCase(Locale.ROOT);
        // 飛行系はチェック名が移動系と紛らわしいため最初に判定する
        for (String key : flyChecks) {
            if (lower.contains(key)) {
                return AlertType.FLY;
            }
        }
        for (String key : speedChecks) {
            if (lower.contains(key)) {
                return AlertType.SPEED;
            }
        }
        for (String key : nukerChecks) {
            if (lower.contains(key)) {
                return AlertType.NUKER;
            }
        }
        return null;
    }

    private UUID extractUuid(Object grimUser) {
        if (grimUser == null) {
            return null;
        }
        if (grimUser instanceof Player player) {
            return player.getUniqueId();
        }
        Object uuid = invoke(grimUser, "getUniqueId");
        if (uuid instanceof UUID u) {
            return u;
        }
        Object name = invoke(grimUser, "getName");
        if (name instanceof String s) {
            Player player = Bukkit.getPlayerExact(s);
            return player != null ? player.getUniqueId() : null;
        }
        return null;
    }

    private static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String firstString(Object... values) {
        for (Object value : values) {
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    /** 数値を取り出せない場合は -1（＝閾値判定をスキップし、クールダウンのみで抑制）。 */
    private static double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : -1;
    }

    private static List<String> lowerCase(List<String> values) {
        return values.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
