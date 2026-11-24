package me.daifugo.daifugo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 共通の土台：ゲームの状態（変数）、カードのデータ、設定・戦績の読み書き
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public abstract class DaifugoBase extends JavaPlugin implements Listener {

    // ===== 後ろのファイルで中身を書いているメソッドの「予告」 =====
    // （前のファイルから後ろのファイルの処理を呼ぶために必要。中身はそれぞれのファイルにあります）
    abstract boolean isEffectiveRevolution();
    abstract void cancelPhaseTimer();
    abstract void advanceTurn();
    abstract void advanceTurnIndexOnly();
    abstract void broadcastTurnInfo();
    abstract boolean checkGameFinish();
    abstract List<UUID> getActivePlayerUuids();
    abstract UUID getCurrentTurnUuid();
    abstract UUID getNextActivePlayerUuid(UUID current);
    abstract void resetTrick();


    Location tableCenter;
    final List<UUID> joinedPlayers = new ArrayList<>();
    final List<Entity> allEntities = new ArrayList<>();
    Interaction joinInteraction;
    TextDisplay joinDisplay;

    // ゲーム状態管理
    public enum GameState {
        WAITING, EXCHANGING, PLAYING, RESULT
    }
    GameState gameState = GameState.WAITING;

    // 戦績システム
    File statsFile;
    FileConfiguration statsConfig;

    // ルール設定
    final Map<String, Boolean> ruleSettings = new LinkedHashMap<>();
    int jokerCount = 1; // 0, 1, 2
    int suitLockMode = 1; // 0: なし, 1: 通常縛り, 2: 激縛り
    int fourStopMode = 0; // 0: なし, 1: 2枚固定, 2: 倍数(x2)
    int stairsEffectMode = 1; // 0: なし, 1: 8切りのみ, 2: 全て発動
    final List<Entity> hostSettingEntities = new ArrayList<>();
    final Map<UUID, String> ruleToggleActions = new HashMap<>();

    // 中央の場札・ステータス
    final List<TextDisplay> fieldDisplays = new ArrayList<>();
    TextDisplay fieldStatusDisplay;

    public static class Card implements Comparable<Card> {
        final String suit;
        final int number;
        final int normalStrength;

        public Card(String suit, int number) {
            this.suit = suit;
            this.number = number;
            if (suit.equals("JOKER")) {
                this.normalStrength = 14;
            } else if (number == 1) { // A
                this.normalStrength = 12;
            } else if (number == 2) { // 2
                this.normalStrength = 13;
            } else { // 3〜13
                this.normalStrength = number - 2;
            }
        }

        public String getSuit() {
            return suit;
        }

        public int getNumber() {
            return number;
        }

        public int getNormalStrength() {
            return normalStrength;
        }

        public boolean isJoker() {
            return "JOKER".equals(suit);
        }

        public int getStrength(boolean effectiveRevolution) {
            if (isJoker()) return 14;
            if (effectiveRevolution) {
                return 14 - normalStrength;
            }
            return normalStrength;
        }

        public NamedTextColor getSuitColor() {
            return switch (suit) {
                case "♠" -> NamedTextColor.BLUE;
                case "♥" -> NamedTextColor.RED;
                case "♦" -> NamedTextColor.GOLD;
                case "♣" -> NamedTextColor.DARK_GREEN;
                default -> NamedTextColor.LIGHT_PURPLE; // JOKER
            };
        }

        public String getDisplayLabel() {
            if (isJoker()) return "JK\n★";
            String numStr = switch (number) {
                case 1 -> " A";
                case 11 -> " J";
                case 12 -> " Q";
                case 13 -> " K";
                case 10 -> "10";
                default -> " " + number;
            };
            return " " + suit + " \n" + numStr;
        }

        public String getShortLabel() {
            if (isJoker()) return "JK";
            String numStr = switch (number) {
                case 1 -> "A";
                case 11 -> "J";
                case 12 -> "Q";
                case 13 -> "K";
                default -> String.valueOf(number);
            };
            return suit + numStr;
        }

        private static int getSuitOrder(String s) {
            return switch (s) {
                case "♠" -> 4;
                case "♥" -> 3;
                case "♦" -> 2;
                case "♣" -> 1;
                default -> 5; // JOKER
            };
        }

        @Override
        public int compareTo(Card o) {
            int diff = Integer.compare(this.normalStrength, o.normalStrength);
            if (diff != 0) return diff;
            return Integer.compare(getSuitOrder(this.suit), getSuitOrder(o.suit));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Card card = (Card) o;
            return number == card.number && Objects.equals(suit, card.suit);
        }

        @Override
        public int hashCode() {
            return Objects.hash(suit, number);
        }
    }

    static class CardInstance {
        final Card card;
        final TextDisplay display;
        final TextDisplay backDisplay;
        final Interaction interaction;
        boolean selected = false;

        CardInstance(Card card, TextDisplay display, TextDisplay backDisplay, Interaction interaction) {
            this.card = card;
            this.display = display;
            this.backDisplay = backDisplay;
            this.interaction = interaction;
        }
    }

    static class DisconnectData {
        final List<Card> hand;
        final Location seat;
        final long disconnectTime;

        DisconnectData(List<Card> hand, Location seat, long disconnectTime) {
            this.hand = hand;
            this.seat = seat;
            this.disconnectTime = disconnectTime;
        }
    }

    final Map<UUID, List<Card>> playerDataHands = new HashMap<>();
    final Map<UUID, Location> playerSeatLocations = new HashMap<>();
    final Map<UUID, ArmorStand> playerSeatEntities = new HashMap<>();
    final Map<UUID, List<CardInstance>> playerHands = new HashMap<>();
    final Map<UUID, List<Entity>> playerButtonEntities = new HashMap<>();
    final Map<UUID, TextDisplay> playerHeadDisplays = new HashMap<>();
    final Map<UUID, String> buttonActions = new HashMap<>();

    // ゲーム進行用フィールド
    int currentTurnIndex = 0;
    boolean isTurnReversed = false;
    List<Card> currentFieldCards = new ArrayList<>();
    boolean currentFieldIsStairs = false;
    UUID lastPlayPlayerUuid = null;
    int consecutivePassCount = 0;

    // パス縛り用
    final Set<UUID> passedPlayers = new HashSet<>();

    // 縛り状態管理
    boolean isGekiLocked = false;
    boolean isNumberLocked = false;
    final Set<String> lockedSuits = new HashSet<>();

    // 4止め待機フラグ
    boolean waitingForFourStop = false;
    int eightCardCount = 0;

    // 降参2段階確認用
    final Map<UUID, Long> surrenderConfirmMap = new HashMap<>();

    // 手札交換フェーズ
    final Map<UUID, Integer> pendingExchangeRequired = new HashMap<>();
    final Map<UUID, UUID> exchangePairs = new HashMap<>();
    final Map<UUID, List<Card>> richSelectedCards = new HashMap<>();
    final Map<UUID, List<Card>> poorCardsToGive = new HashMap<>();

    // 役効果の連鎖キュー
    final List<String> pendingEffectQueue = new ArrayList<>();
    int queuedSevenCount = 0;
    int queuedTenCount = 0;
    int queuedQueenCount = 0;

    // 特殊状態：7渡し / 10捨て / Qボンバー
    boolean waitingForSevenPass = false;
    int sevenPassRequiredCount = 0;
    UUID sevenPassPlayerUuid = null;

    boolean waitingForTenDiscard = false;
    int tenDiscardRequiredCount = 0;
    UUID tenDiscardPlayerUuid = null;

    final List<Entity> qBomberEntities = new ArrayList<>();
    final Map<UUID, Integer> qBomberActionMap = new HashMap<>();
    boolean waitingForQBomber = false;
    int qBomberRemainingPicks = 0;
    UUID qBomberPlayerUuid = null;

    // 連戦・階級管理
    final List<UUID> previousGameRankings = new ArrayList<>();
    final List<UUID> finishedRankings = new ArrayList<>();
    final List<UUID> foulPlayers = new ArrayList<>();

    // 切断復帰管理
    final Map<UUID, DisconnectData> disconnectedPlayers = new HashMap<>();
    BukkitTask disconnectCheckTask;

    // ターン制限時間タイマー（30秒）
    BukkitTask turnTimerTask;
    int currentTurnSeconds = 30;

    // 革命パーティクルタスク
    BukkitTask particleTask;

    // 特殊ルール動的状態
    boolean isRevolution = false;
    boolean isElevenBack = false;

    static final int MAX_PLAYERS = 6;
    static final double TABLE_RADIUS = 2.8;
    static final int CARDS_PER_ROW = 7;
    static final double CARD_SPACING_X = 0.50;
    static final double CARD_SPACING_Y = 0.78;
    static final String ENTITY_TAG = "daifugo_entity";

    // ===== 修正版で追加したフィールド =====
    // 7渡し・10捨て・Qボンバー・手札交換の制限時間（秒）。放置でゲームが止まるのを防ぐ
    static final int PHASE_TIME_LIMIT = 30;
    BukkitTask phaseTimerTask;
    int phaseSeconds = PHASE_TIME_LIMIT;
    // 7/10/Qと5スキップが同時に出たとき、5スキップの枚数を覚えておく
    int queuedFiveCount = 0;
    // プラグイン側で降ろすときだけ降車を許可するためのリスト
    final Set<UUID> allowDismount = new HashSet<>();
    // /daifugo clear のたびに増える番号（古い遅延処理が新しい卓を壊さないように）
    int sessionId = 0;
    // 管理者権限（未登録の権限はOPだけが持つ扱いになるため、plugin.yml の変更なしで動く）
    static final String ADMIN_PERMISSION = "daifugo.admin";

    void loadRuleConfig() {
        FileConfiguration config = getConfig();
        jokerCount = config.getInt("rules.joker_count", 1);
        suitLockMode = config.getInt("rules.suit_lock_mode", 1);
        fourStopMode = config.getInt("rules.four_stop_mode", 0);
        stairsEffectMode = config.getInt("rules.stairs_effect_mode", 1);

        ruleSettings.put("revolution", config.getBoolean("rules.revolution", true));
        ruleSettings.put("stairs", config.getBoolean("rules.stairs", true));
        ruleSettings.put("stairs_revolution", config.getBoolean("rules.stairs_revolution", true));
        ruleSettings.put("eight_cut", config.getBoolean("rules.eight_cut", true));
        ruleSettings.put("eleven_back", config.getBoolean("rules.eleven_back", true));
        ruleSettings.put("space_three", config.getBoolean("rules.space_three", true));
        ruleSettings.put("illegal_finish", config.getBoolean("rules.illegal_finish", true));
        ruleSettings.put("pass_lock", config.getBoolean("rules.pass_lock", false));

        ruleSettings.put("five_skip", config.getBoolean("rules.five_skip", false));
        ruleSettings.put("seven_pass", config.getBoolean("rules.seven_pass", false));
        ruleSettings.put("nine_reverse", config.getBoolean("rules.nine_reverse", false));
        ruleSettings.put("ten_discard", config.getBoolean("rules.ten_discard", false));
        ruleSettings.put("q_bomber", config.getBoolean("rules.q_bomber", false));
        ruleSettings.put("down_number", config.getBoolean("rules.down_number", false));

        ruleSettings.put("continue_game", config.getBoolean("rules.continue_game", true));
        ruleSettings.put("card_exchange", config.getBoolean("rules.card_exchange", true));
        ruleSettings.put("miyakooti", config.getBoolean("rules.miyakooti", true));
        ruleSettings.put("daihinmin_start", config.getBoolean("rules.daihinmin_start", true));
    }

    void saveRuleSetting(String key, Object value) {
        getConfig().set("rules." + key, value);
        saveConfig();
    }

    void loadStatsConfig() {
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            try {
                statsFile.getParentFile().mkdirs();
                statsFile.createNewFile();
            } catch (Exception ignored) {}
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    void saveStatsConfig() {
        try {
            statsConfig.save(statsFile);
        } catch (Exception ignored) {}
    }

    void recordGameResult(UUID winnerUuid) {
        // 1人テストプレイでは戦績を記録しない（勝率100%の水増し防止）
        if (joinedPlayers.size() < 2) return;
        for (UUID uuid : joinedPlayers) {
            String path = uuid.toString();
            int plays = statsConfig.getInt(path + ".plays", 0) + 1;
            statsConfig.set(path + ".plays", plays);

            if (uuid.equals(winnerUuid)) {
                int wins = statsConfig.getInt(path + ".wins", 0) + 1;
                statsConfig.set(path + ".wins", wins);
                int streak = statsConfig.getInt(path + ".streak", 0) + 1;
                statsConfig.set(path + ".streak", streak);
            } else {
                statsConfig.set(path + ".streak", 0);
            }
        }
        saveStatsConfig();
    }

    String getRuleNameJa(String key) {
        return switch (key) {
            case "joker_count" -> "ジョーカー";
            case "suit_lock_mode" -> "しばり";
            case "stairs_effect_mode" -> "階段役重複";
            case "four_stop_mode" -> "4止め";
            case "revolution" -> "革命";
            case "stairs" -> "階段";
            case "stairs_revolution" -> "階段革命";
            case "eight_cut" -> "8切り";
            case "eleven_back" -> "11バック";
            case "space_three" -> "スペ3返し";
            case "illegal_finish" -> "反則上がり";
            case "pass_lock" -> "パス縛り";
            case "five_skip" -> "5スキップ";
            case "seven_pass" -> "7渡し";
            case "nine_reverse" -> "9リバース";
            case "ten_discard" -> "10捨て";
            case "q_bomber" -> "Qボンバー";
            case "down_number" -> "ダウンNo.";
            case "continue_game" -> "連戦モード";
            case "card_exchange" -> "手札交換";
            case "miyakooti" -> "都落ち";
            case "daihinmin_start" -> "大貧民親番";
            default -> key;
        };
    }

    boolean isJoined(UUID uuid) {
        return joinedPlayers.contains(uuid);
    }

    void broadcast(Component component) {
        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                p.sendMessage(component);
            }
        }
    }

    Set<String> getCardSuits(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> set = new HashSet<>();
        for (Card c : cards) {
            if (c != null && !c.isJoker() && c.getSuit() != null) {
                set.add(c.getSuit());
            }
        }
        return set;
    }
}
