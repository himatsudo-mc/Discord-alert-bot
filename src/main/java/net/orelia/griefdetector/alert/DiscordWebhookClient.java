package net.orelia.griefdetector.alert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Discord Webhook への送信クライアント。外部ライブラリなしで embed JSON を組み立てて POST する。
 * 呼び出しは必ず非同期スレッドから行うこと（AlertService が保証する）。
 */
public final class DiscordWebhookClient {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz").withZone(ZoneId.systemDefault());

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final Logger logger;

    public DiscordWebhookClient(Logger logger) {
        this.logger = logger;
    }

    /** アラートを embed として送信する。失敗してもサーバー動作には影響させず、ログに残すのみ。 */
    public void send(String webhookUrl, String username, Alert alert) {
        String payload = buildPayload(username, alert);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 429) {
                logger.warning("[GriefDetector] Discord Webhook がレート制限されました (429)。このアラートは破棄されます。");
            } else if (code < 200 || code >= 300) {
                logger.warning("[GriefDetector] Discord Webhook 送信に失敗しました: HTTP " + code + " " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warning("[GriefDetector] Discord Webhook 送信に失敗しました: " + e.getMessage());
        }
    }

    private String buildPayload(String username, Alert alert) {
        String time = TIME_FORMAT.format(alert.time());
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"username\":").append(json(username)).append(',');
        sb.append("\"embeds\":[{");
        sb.append("\"title\":")
                .append(json(alert.type().severity().icon() + " 荒らし検知: " + alert.type().label()))
                .append(',');
        sb.append("\"color\":").append(alert.type().severity().color()).append(',');
        sb.append("\"fields\":[");
        sb.append(field("検知時間", time)).append(',');
        sb.append(field("プレイヤーID", alert.playerName() + " (" + alert.playerId() + ")")).append(',');
        sb.append(field("アラート発生座標", alert.coordinates())).append(',');
        sb.append(field("IPアドレス", alert.ipAddress())).append(',');
        sb.append(field("荒らし種別", alert.type().label())).append(',');
        sb.append(field("詳細", alert.detail()));
        sb.append("],");
        sb.append("\"timestamp\":").append(json(alert.time().toString()));
        sb.append("}]}");
        return sb.toString();
    }

    private String field(String name, String value) {
        return "{\"name\":" + json(name) + ",\"value\":" + json(value) + ",\"inline\":false}";
    }

    /** 最小限の JSON 文字列エスケープ。 */
    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
