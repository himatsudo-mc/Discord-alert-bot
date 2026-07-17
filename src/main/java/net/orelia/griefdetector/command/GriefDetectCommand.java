package net.orelia.griefdetector.command;

import java.util.List;
import java.util.Map;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.orelia.griefdetector.GriefDetectorPlugin;
import net.orelia.griefdetector.alert.Alert;
import net.orelia.griefdetector.alert.AlertType;

/** /griefdetect <reload|status|test> */
public final class GriefDetectCommand implements TabExecutor {

    private static final List<String> SUB_COMMANDS = List.of("reload", "status", "test");

    private final GriefDetectorPlugin plugin;

    public GriefDetectCommand(GriefDetectorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage("§a[GriefDetector] 設定を再読み込みし、全モジュールを再起動しました。");
                return true;
            }
            case "status" -> {
                sender.sendMessage("§6[GriefDetector] 稼働状況:");
                for (Map.Entry<String, String> entry : plugin.getDetectorManager().statusLines().entrySet()) {
                    sender.sendMessage("§7 - §e" + entry.getKey() + "§7: §f" + entry.getValue());
                }
                return true;
            }
            case "test" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§c[GriefDetector] test はゲーム内から実行してください。");
                    return true;
                }
                boolean sent = plugin.getAlertService().raise(
                        Alert.of(player, AlertType.TNT, "テスト通知（/griefdetect test）"));
                sender.sendMessage(sent
                        ? "§a[GriefDetector] テストアラートを送信しました。Discord を確認してください。"
                        : "§e[GriefDetector] クールダウン中のため送信をスキップしました。");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUB_COMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
