# 🃏 Minecraft Daifugo Plugin（マイクラ 大富豪プラグイン）

マインクラフト（Minecraft）のサーバー内で、本格的なトランプゲーム「大富豪（大貧民）」が遊べる **PaperMC対応プラグイン** です。  
ホログラムUIによる直感的な操作と、豊富なローカルルールのカスタマイズに対応しています。

<img width="2536" height="1383" alt="スクリーンショット 2026-11-16 143917" src="https://github.com/user-attachments/assets/e7425e2c-e963-47ae-bb69-d4d0d5e0f7e3" />
<img width="2508" height="1424" alt="スクリーンショット 2026-11-16 143853" src="https://github.com/user-attachments/assets/14fe7869-cf0f-4d6c-8e2f-118ecaec6c17" />

## ✨ 主な特徴 (Features)

*   **2〜6人でのマルチプレイ対戦:** 円形テーブルを囲んで臨場感のある対戦が可能
*   **直感的なホログラムUI:** チャットコマンドを覚えなくても、ゲーム内パネルからカード選択やパスが可能
*   **観戦モード搭載:** 観戦者からは手札の裏面が見える仕様で、公平な観戦を実現
*   **豊富なローカルルール設定:** ゲーム内の設定パネルからON/OFFを切り替え可能
    *   革命 / 8切り / 4止め / 5スキップ / 7渡し / 9リバース / Qボンバー / ジョーカー
    *   連戦機能（階級に応じたカード交換対応）
*   **安定したマルチプレイ処理:** 途中切断・再接続時のゲーム進行維持に対応

![ルール設定画面](images/rules.png)

## ⚙️ 動作環境 (Requirements)

*   **サーバー:** PaperMC（Spigot等での動作は要確認）
*   **対応バージョン:** Minecraft `26.3`（※動作検証済みのバージョンを記載）
*   **Java:** Java 21 以上

## 📥 導入方法 (Installation)

1.  [Releases](../../releases) ページから最新の `.jar` ファイル（例: `Daifugo-1.0-SNAPSHOT.jar`）をダウンロードします。
2.  ダウンロードした `.jar` ファイルを、サーバーの `plugins` フォルダに配置します。
3.  サーバーを再起動して導入完了です。

## 🎮 コマンド一覧 (Commands)

| コマンド | 説明 | 権限 |
| :--- | :--- | :--- |
| `/daifugo join` | 大富豪のゲームに参加する | 全員 |
| `/daifugo start` | ゲームを開始する | 全員 / OP |
| `/daifugo leave` | ゲームから退出する | 全員 |
| `/daifugo rules` | ローカルルールの設定パネルを開く | OP |

※実際に実装しているコマンドに合わせて書き換えてください。

## 🛠️ 開発・技術スタック (Tech Stack)

*   **Language:** Java
*   **API:** Paper API
*   **Build Tool:** Gradle / Maven

## 📝 ライセンス (License)

MIT License（※設定しているライセンスに合わせて変更してください）

---
**Tags:** Minecraft, マインクラフト, マイクラ, プラグイン, PaperMC, 大富豪, 大貧民, トランプ, ミニゲーム, Daifugo, President, Card Game
