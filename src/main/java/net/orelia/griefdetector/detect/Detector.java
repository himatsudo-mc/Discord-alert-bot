package net.orelia.griefdetector.detect;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 検知モジュールの共通インターフェース。
 * 新しい検知項目（例: Xray）を追加する場合は、この実装を1つ書き、
 * DetectorManager に登録し、config.yml に detectors.<name> セクションを足すだけでよい。
 */
public interface Detector {

    /** config.yml の detectors.<name> に対応する識別子。 */
    String name();

    /**
     * モジュールを起動する。リスナー登録・スケジューラ登録はこの中でのみ行うこと。
     * enabled: false のモジュールにはそもそも呼ばれない（コストゼロ保証）。
     *
     * @param section detectors.<name> セクション（存在しない場合は空セクション）
     * @return 実際に起動した場合 true（mode: off や Grim 専任で自前処理が不要な場合は false を返してよい）
     */
    boolean start(ConfigurationSection section);

    /** リスナー・タスク・内部状態をすべて解除する。start していなくても安全に呼べること。 */
    void stop();

    /** 現在の稼働状況の説明（/griefdetect status 用）。 */
    String statusLine();
}
