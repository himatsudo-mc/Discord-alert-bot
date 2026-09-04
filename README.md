# GriefDetector (Discord-alert-bot)

荒らし行為を検知した瞬間に Discord へ通知する、完全独立のスタンドアロン Paper プラグイン。

防御は WorldGuard、アンチチートは Grim、ロールバックは CoreProtect、最終防衛は定期バックアップが担う前提で、
本プラグインの役割は **「異常を検知し、即座に Discord へ通知する」こと一本** に絞っている。

## 検知対象

| モジュール | 内容 | 実装方式 |
|---|---|---|
| `tnt` | プレイヤーが着火した TNT の異常な連続使用 | 完全自前実装 |
| `speed` | エリトラ・俊敏効果なしでの規定速度超過（SpeedHack系） | ハイブリッド（Tier A: Grim / Tier B: 自前） |
| `nuker` | 人間では不可能な速度でのブロック破壊 | ハイブリッド（Tier A: Grim / Tier B: 自前） |
| `fly` | エリトラ以外での不正飛行（FlyHack系） | ハイブリッド（Tier A: Grim / Tier B: 自前） |
| `arson` | TNT以外（フリント&スチール等）での異常な連続放火 | 完全自前実装 |
| `xray` | 貴重鉱石の異常な採掘頻度（統計的異常。warning 扱い） | 完全自前実装（GrimAC非依存） |
| `combat` | 近接戦闘系（Reach/Killaura/オートクリッカー等） | GrimAC 連携専任（自前ロジックなし） |

## GrimAC との責務分担

- **本プラグイン = 検知の通知**: Grim の違反フラグも本プラグインが受け取り、必須5項目付きの統一フォーマットで Discord へ通知する。通知の重複を避けるため、Grim 標準の Discord 通知（`plugins/GrimAC/discord.yml`）は無効のままを推奨。
- **Grim = プレイヤーへの対策**: Grim はチート挙動をリアルタイムでセットバック（巻き戻し）して無効化する。キック/BAN などの処罰は Grim の `punishments.yml` で設定する。

## 通知内容（必須5項目）

すべてのアラートに **検知時間 / プレイヤーID / アラート発生座標 / IPアドレス / 荒らし種別** を含む
（加えて検知の詳細メッセージを添付）。Discord には embed 形式で送信される。

アラートには重要度（severity）があり、embed の色で区別される。
- **alert**（赤・🚨）: TNT/Speed/Nuker/Fly/Arson/Combat。確度の高い荒らし行為。
- **warning**（橙・⚠️）: Xray のみ。統計的異常検知であり「たまたま」の可能性を否定できないため。

## Grim 併用のハイブリッド方式（Tier A / Tier B）

- **Tier A（推奨・優先）**: GrimAC が導入されていれば、Grim の違反フラグイベント（FlagEvent）をフックして
  そのまま Discord アラートに変換する。ラグ補正・パケット解析込みの Grim の判定をそのまま使うため高精度。
  - Grim のプラグイン間 API はバージョンにより提供状況が異なるため（スパイク調査の結論）、
    コンパイル時依存は持たず **実行時リフレクション** で候補クラスを探索してフックする。
    フックできなければ自動的に Tier B へフォールバックする。
- **Tier B（フォールバック）**: 移動距離/秒の計測・ブロック破壊間隔の計測による簡易な自前ロジック。
  **補助的検知であり誤検知しうる**（エリトラ・俊敏・騎乗・テレポート直後などは除外済み）。

`speed.mode` / `nuker.mode` / `fly.mode` を `auto | grim | custom | off` で切り替え可能。
デフォルトの `auto` は Grim があれば Grim 優先で Tier B を自動無効化する。

`combat`（近接戦闘系）は GrimAC 連携専任で、`mode` は `auto | grim | off` のみサポートする
（`custom` は自前ロジック未実装のため警告ログを出して起動しない）。Reach/Killaura/
オートクリッカー等の簡易自前判定は誤検知率が高いため、あえて Tier B を持たない設計とした。

TNT・放火（arson）検知はアンチチートの範疇ではない「荒らし行動」そのものなので、常に自前実装で動作する。
Xray 検知も同様に GrimAC 非依存の完全自前実装（統計的な採掘パターン検知）。

## 導入

1. `GriefDetector-x.y.z.jar` を `plugins/` に配置してサーバーを起動
2. 初回起動で `plugins/GriefDetector/config.yml` が自動生成される
3. `discord.webhook-url` に Discord の Webhook URL を設定
4. `/griefdetect reload` で反映（再起動不要）

依存プラグインはなし。`softdepend: [GrimAC, WorldGuard, CoreProtect]` のみの疎結合で、
いずれも未導入でも単体で動作する。

## コマンド

| コマンド | 説明 | 権限 |
|---|---|---|
| `/griefdetect reload` | config.yml を再読み込みし全モジュール再起動 | `griefdetector.admin`（デフォルト: OP） |
| `/griefdetect status` | 各モジュールの稼働状況を表示 | 同上 |
| `/griefdetect test` | テストアラートを Discord へ送信 | 同上 |

ゲーム内アラート通知は `griefdetector.notify` 権限（デフォルト: OP）を持つプレイヤーに表示される。

OP、および `griefdetector.bypass` 権限（デフォルト: OP）を持つプレイヤーは、全検知モジュール
（Fly/Speed/Nuker/TNT/Arson/Xray/Combat、GrimAC 連携含む）の対象から除外される。
管理作業中の誤検知を防ぐための措置。

## 設定（config.yml 抜粋）

```yaml
enabled: true            # マスタースイッチ（全モジュール即時停止）
discord:
  webhook-url: ""        # Discord Webhook URL
  cooldown-seconds: 60   # 同一プレイヤー・同一種別の再通知間隔
grim:
  combat-checks: [Killaura, Reach, HitBox, AutoClicker, Aim, Rotation]
detectors:
  tnt:
    enabled: true
    window-seconds: 60
    max-ignitions: 5
  speed:
    enabled: true
    mode: auto           # auto | grim | custom | off
    max-horizontal-blocks-per-second: 12.0
    sustained-seconds: 3
  nuker:
    enabled: true
    mode: auto
    window-seconds: 2
    max-breaks: 30
  fly:
    enabled: true
    mode: auto
    max-airborne-seconds: 5
  arson:
    enabled: true
    window-seconds: 60
    max-ignitions: 5
  xray:
    enabled: true         # 常に warning（橙）として通知
    window-seconds: 300
    max-ore-breaks: 8
    ores: [DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, ANCIENT_DEBRIS, EMERALD_ORE, DEEPSLATE_EMERALD_ORE]
  combat:
    enabled: true
    mode: auto           # auto | grim | off（custom は未対応）
```

## モジュール追加の指針

新しい検知項目（例: Xray検知）を追加する場合:

1. `net.orelia.griefdetector.detect.Detector` インターフェースの実装を1つ書く
2. `GriefDetectorPlugin#onEnable` で `detectorManager.register(...)` する
3. `config.yml` に `detectors.<name>.enabled` セクションを追加する

`enabled: false` のモジュールはリスナー登録・スケジューラ登録そのものが行われず、コストゼロで無効化される。

## ビルド

Java 21 / Paper API 1.21.x / Gradle + Shadow。

```bash
./gradlew build
# → build/libs/GriefDetector-x.y.z.jar
```
