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
 * ゲーム進行の担当：開始・手番・パス・上がり・降参・リザルト・参加/退出
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public abstract class DaifugoGame extends DaifugoEffects {


    /** 現在の手番プレイヤー。番号がずれていても落ちないように補正する */
    UUID getCurrentTurnUuid() {
        if (joinedPlayers.isEmpty()) return null;
        if (currentTurnIndex < 0 || currentTurnIndex >= joinedPlayers.size()) {
            currentTurnIndex = 0;
        }
        return joinedPlayers.get(currentTurnIndex);
    }

    /** 参加リストに残っているオフラインのプレイヤーを外す（開始前に呼ぶ） */
    void removeOfflinePlayers() {
        List<UUID> offline = new ArrayList<>();
        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) offline.add(uuid);
        }
        if (offline.isEmpty()) return;
        for (UUID uuid : offline) {
            joinedPlayers.remove(uuid);
            playerDataHands.remove(uuid);
            playerSeatLocations.remove(uuid);
            disconnectedPlayers.remove(uuid);
            removePlayerUI(uuid);
            removeHeadDisplay(uuid);
            ArmorStand seat = playerSeatEntities.remove(uuid);
            if (seat != null && seat.isValid()) seat.remove();
        }
        broadcast(Component.text("[大富豪] オフラインの参加者 " + offline.size() + "人 を参加リストから外しました。", NamedTextColor.GRAY));
        updateJoinHologramText();
        renderHostRuleSettings();
    }

    /**
     * 降参・切断タイムアウト共通の「棄権（反則負け扱い）」処理。
     * 参加リストからは消さないので、手番の番号がずれない。
     */
    void forfeitPlayer(UUID uuid, Component message) {
        if (!isJoined(uuid)) return;
        if (finishedRankings.contains(uuid) || foulPlayers.contains(uuid)) return;
        if (gameState != GameState.PLAYING && gameState != GameState.EXCHANGING) return;

        boolean wasTurn = uuid.equals(getCurrentTurnUuid());
        boolean wasEffectOwner = (waitingForSevenPass && uuid.equals(sevenPassPlayerUuid))
                || (waitingForTenDiscard && uuid.equals(tenDiscardPlayerUuid))
                || (waitingForQBomber && uuid.equals(qBomberPlayerUuid));

        foulPlayers.add(uuid);
        removePlayerUI(uuid);
        broadcast(message);
        for (UUID u : joinedPlayers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
        }

        if (wasEffectOwner) {
            waitingForSevenPass = false;
            sevenPassPlayerUuid = null;
            waitingForTenDiscard = false;
            tenDiscardPlayerUuid = null;
            closeQBomberKeypad();
            pendingEffectQueue.clear();
            queuedFiveCount = 0;
            cancelPhaseTimer();
        }

        // 残り1人になったら、自動クリアではなく正式にリザルトを出す
        if (checkGameFinish()) return;

        if (gameState == GameState.EXCHANGING) {
            exchangePairs.remove(uuid);
            pendingExchangeRequired.remove(uuid);
            richSelectedCards.remove(uuid);
            if (richSelectedCards.keySet().containsAll(exchangePairs.keySet())) {
                cancelPhaseTimer();
                executeAllExchanges();
            } else {
                updateAllScoreboards();
                updateAllHeadDisplays();
            }
            return;
        }

        if (wasTurn || wasEffectOwner) {
            advanceTurn();
        } else {
            updateAllScoreboards();
            updateAllHeadDisplays();
        }
    }

    void checkDisconnectTimeouts() {
        if (disconnectedPlayers.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, DisconnectData> entry : disconnectedPlayers.entrySet()) {
            if (now - entry.getValue().disconnectTime > 60000) {
                toRemove.add(entry.getKey());
            }
        }

        for (UUID uuid : toRemove) {
            disconnectedPlayers.remove(uuid);
            if (!isJoined(uuid)) continue;

            if (gameState == GameState.PLAYING || gameState == GameState.EXCHANGING) {
                // 対戦中は参加リストから消さず「棄権」にする（手番の番号ずれ＝進行停止を防ぐ）
                forfeitPlayer(uuid, Component.text("[大富豪] 切断から60秒経過したため、棄権（反則負け扱い）になりました。", NamedTextColor.RED, TextDecoration.BOLD));
                continue;
            }

            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                leavePlayer(p);
            } else {
                joinedPlayers.remove(uuid);
                playerDataHands.remove(uuid);
                playerSeatLocations.remove(uuid);
                removePlayerUI(uuid);
                removeHeadDisplay(uuid);
                if (tableCenter != null) {
                    updateJoinHologramText();
                    renderHostRuleSettings();
                }
            }
        }
    }

    void joinPlayer(Player player) {
        if (joinedPlayers.size() >= MAX_PLAYERS) {
            player.sendMessage(Component.text("[大富豪] 満員です（最大 " + MAX_PLAYERS + "人）。", NamedTextColor.RED));
            return;
        }
        UUID uuid = player.getUniqueId();
        joinedPlayers.add(uuid);

        boolean isHost = joinedPlayers.size() == 1;
        String hostMsg = isHost ? " (あなたがホストです。目の前のパネルからルールを変更できます)" : "";
        player.sendMessage(Component.text("[大富豪] 参加しました！（" + joinedPlayers.size() + "人目）" + hostMsg, NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);

        updateJoinHologramText();
        rearrangeTable();
        renderHostRuleSettings();
    }

    void leavePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!isJoined(uuid)) return;
        boolean wasHost = !joinedPlayers.isEmpty() && joinedPlayers.get(0).equals(uuid);

        safelyDismountPlayer(uuid);

        joinedPlayers.remove(uuid);
        playerDataHands.remove(uuid);
        playerSeatLocations.remove(uuid);
        removePlayerUI(uuid);
        resetScoreboard(player);

        player.sendMessage(Component.text("[大富豪] 退出しました。", NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 1f);

        if (gameState == GameState.PLAYING || gameState == GameState.EXCHANGING) {
            List<UUID> active = getActivePlayerUuids();
            if (active.size() <= 1) {
                broadcast(Component.text("[大富豪] プレイ中のプレイヤーが1人になったため、ゲームを終了して自動クリアしました。", NamedTextColor.YELLOW, TextDecoration.BOLD));
                clearAll(tableCenter);
                return;
            }

            if (!joinedPlayers.isEmpty()) {
                if (currentTurnIndex >= joinedPlayers.size()) {
                    currentTurnIndex = 0;
                }
                broadcastTurnInfo();
                updateAllScoreboards();
                updateAllHeadDisplays();
            }
        } else {
            if (wasHost && !joinedPlayers.isEmpty()) {
                Player newHost = Bukkit.getPlayer(joinedPlayers.get(0));
                if (newHost != null) {
                    newHost.sendMessage(Component.text("[大富豪] あなたが新しいホストになりました！", NamedTextColor.GOLD, TextDecoration.BOLD));
                    newHost.playSound(newHost.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
                }
            }
            renderHostRuleSettings();
        }

        updateJoinHologramText();
        rearrangeTable();
    }

    void startGame() {
        gameState = GameState.PLAYING;
        isRevolution = false;
        isElevenBack = false;
        isTurnReversed = false;
        lockedSuits.clear();
        isGekiLocked = false;
        isNumberLocked = false;
        waitingForFourStop = false;
        eightCardCount = 0;
        passedPlayers.clear();
        surrenderConfirmMap.clear();
        pendingEffectQueue.clear();
        waitingForSevenPass = false;
        waitingForTenDiscard = false;
        waitingForQBomber = false;
        qBomberRemainingPicks = 0;
        qBomberPlayerUuid = null;
        pendingExchangeRequired.clear();
        exchangePairs.clear();
        richSelectedCards.clear();
        poorCardsToGive.clear();
        finishedRankings.clear();
        foulPlayers.clear();

        removeHostRuleSettings();

        queuedFiveCount = 0;
        cancelPhaseTimer();

        // 参加ホログラムは対戦中は不要なので消す（空の黒い板が残らないように）
        if (joinDisplay != null) {
            if (joinDisplay.isValid()) joinDisplay.remove();
            allEntities.remove(joinDisplay);
            joinDisplay = null;
        }
        if (joinInteraction != null) {
            if (joinInteraction.isValid()) joinInteraction.remove();
            allEntities.remove(joinInteraction);
            joinInteraction = null;
        }

        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(Component.text("=================================", NamedTextColor.GOLD, TextDecoration.BOLD));
                p.sendMessage(Component.text("      ★ 大富豪 対戦開始 ★       ", NamedTextColor.YELLOW, TextDecoration.BOLD));
                List<String> activeRules = new ArrayList<>();
                activeRules.add("ジョーカー: " + jokerCount + "枚");
                String sName = switch (suitLockMode) {
                    case 0 -> "なし";
                    case 1 -> "通常縛り";
                    default -> "激縛り";
                };
                activeRules.add("縛り: " + sName);
                String stName = switch (stairsEffectMode) {
                    case 0 -> "なし";
                    case 1 -> "8切りのみ";
                    default -> "全て発動";
                };
                activeRules.add("階段役重複: " + stName);
                String fName = switch (fourStopMode) {
                    case 0 -> "なし";
                    case 1 -> "4止め (2枚固定)";
                    default -> "4止め (倍数x2)";
                };
                if (fourStopMode > 0) activeRules.add("✔ " + fName);
                for (Map.Entry<String, Boolean> entry : ruleSettings.entrySet()) {
                    if (entry.getValue()) activeRules.add("✔ " + getRuleNameJa(entry.getKey()));
                }
                p.sendMessage(Component.text("採用ルール: " + String.join(", ", activeRules), NamedTextColor.GRAY));
                p.sendMessage(Component.text("=================================", NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }

        List<Card> deck = new ArrayList<>();
        String[] suits = {"♠", "♥", "♦", "♣"};
        for (String s : suits) {
            for (int num = 1; num <= 13; num++) {
                deck.add(new Card(s, num));
            }
        }
        for (int j = 0; j < jokerCount; j++) {
            deck.add(new Card("JOKER", 99));
        }
        Collections.shuffle(deck);

        int playerCount = joinedPlayers.size();
        for (int i = 0; i < playerCount; i++) {
            playerDataHands.put(joinedPlayers.get(i), new ArrayList<>());
        }

        if (playerCount == 1) {
            for (int i = 0; i < 10; i++) {
                playerDataHands.get(joinedPlayers.get(0)).add(deck.get(i));
            }
        } else {
            for (int i = 0; i < deck.size(); i++) {
                UUID target = joinedPlayers.get(i % playerCount);
                playerDataHands.get(target).add(deck.get(i));
            }
        }

        boolean exchangeActive = false;
        if (ruleSettings.getOrDefault("card_exchange", true) && previousGameRankings.size() == playerCount && playerCount >= 2) {
            exchangeActive = initCardExchangePhase();
            if (exchangeActive) gameState = GameState.EXCHANGING;
        }

        for (UUID uuid : joinedPlayers) {
            Collections.sort(playerDataHands.get(uuid));
            Location seatLoc = playerSeatLocations.get(uuid);
            if (seatLoc != null) {
                renderPlayerHand(uuid, seatLoc);
                spawnActionButtons(uuid, seatLoc);
                spawnHeadDisplay(uuid, seatLoc);
            }
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(Component.text("[大富豪] カードが配られました！", NamedTextColor.GREEN));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }

        UUID starterUuid = null;
        if (previousGameRankings.size() >= 2 && ruleSettings.getOrDefault("daihinmin_start", true)) {
            UUID daihinminUuid = previousGameRankings.get(previousGameRankings.size() - 1);
            if (isJoined(daihinminUuid)) {
                starterUuid = daihinminUuid;
                Player sPlayer = Bukkit.getPlayer(starterUuid);
                String sName = (sPlayer != null) ? sPlayer.getName() : "前大貧民";
                broadcast(Component.text("[大富豪] 前ゲームの大貧民 " + sName + " からスタートします！", NamedTextColor.YELLOW));
            }
        }

        if (starterUuid == null) {
            for (UUID uuid : joinedPlayers) {
                List<Card> hand = playerDataHands.get(uuid);
                if (hand != null) {
                    for (Card c : hand) {
                        if (c.getSuit().equals("♦") && c.getNumber() == 3) {
                            starterUuid = uuid;
                            break;
                        }
                    }
                }
                if (starterUuid != null) break;
            }
            if (starterUuid != null) {
                Player sPlayer = Bukkit.getPlayer(starterUuid);
                String sName = (sPlayer != null) ? sPlayer.getName() : "ダイヤの3所持者";
                broadcast(Component.text("[大富豪] ダイヤの3 (♦3) を所持している " + sName + " からスタートします！", NamedTextColor.YELLOW));
            }
        }

        currentTurnIndex = (starterUuid != null) ? joinedPlayers.indexOf(starterUuid) : 0;
        consecutivePassCount = 0;
        currentFieldCards.clear();
        currentFieldIsStairs = false;

        updateCentralStatus();
        updateAllScoreboards();
        updateAllHeadDisplays();

        if (!exchangeActive) {
            final int session = sessionId;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (session == sessionId && gameState == GameState.PLAYING) broadcastTurnInfo();
            }, 1L);
        } else {
            // 手札交換にも制限時間をつける（放置・切断で止まらないように）
            startPhaseTimer(null, "EXCHANGE");
        }
    }

    List<UUID> getActivePlayerUuids() {
        List<UUID> active = new ArrayList<>();
        for (UUID uuid : joinedPlayers) {
            if (!finishedRankings.contains(uuid) && !foulPlayers.contains(uuid)) {
                active.add(uuid);
            }
        }
        return active;
    }

    void resetTrick() {
        clearFieldCards();
        lockedSuits.clear();
        isGekiLocked = false;
        isNumberLocked = false;
        isElevenBack = false;
        waitingForFourStop = false;
        passedPlayers.clear();
        consecutivePassCount = 0;
        updateCentralStatus();
    }

    boolean isValidTurnPlayer(UUID uuid, List<UUID> activeUuids) {
        if (uuid == null) return false;
        if (!activeUuids.contains(uuid)) return false;
        if (ruleSettings.getOrDefault("pass_lock", false) && passedPlayers.contains(uuid)) {
            return false;
        }
        return true;
    }

    void broadcastTurnInfo() {
        List<UUID> active = getActivePlayerUuids();
        if (active.isEmpty()) return;

        UUID turnUuid = getCurrentTurnUuid();
        if (!isValidTurnPlayer(turnUuid, active)) {
            advanceTurnIndexOnly();
            turnUuid = getCurrentTurnUuid();
        }

        startTurnTimer(turnUuid);

        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                if (uuid.equals(turnUuid)) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.5f);
                    p.showTitle(Title.title(
                            Component.text("【 あなたの手番 】", NamedTextColor.YELLOW, TextDecoration.BOLD),
                            Component.text("カードを選択して提出してください", NamedTextColor.WHITE),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(300))
                    ));
                } else {
                    Player tp = Bukkit.getPlayer(turnUuid);
                    String tpName = tp != null ? tp.getName() : "プレイヤー";
                    p.sendActionBar(Component.text("現在の手番: " + tpName, NamedTextColor.GRAY));
                }
            }
        }
    }

    void startTurnTimer(UUID turnUuid) {
        if (turnTimerTask != null) turnTimerTask.cancel();
        currentTurnSeconds = 30;

        turnTimerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (gameState != GameState.PLAYING || joinedPlayers.isEmpty()) {
                if (turnTimerTask != null) turnTimerTask.cancel();
                return;
            }

            // ※ 以前は4止め待ちの間もタイマーが止まっていたため、次の人が放置するとゲームが止まっていた
            if (waitingForSevenPass || waitingForTenDiscard || waitingForQBomber || !pendingExchangeRequired.isEmpty()) {
                return;
            }

            // 手番が別の人に移っているのに古いタイマーが残っていた場合は、正しい人のタイマーに作り直す
            UUID nowTurn = getCurrentTurnUuid();
            if (nowTurn == null || !nowTurn.equals(turnUuid)) {
                broadcastTurnInfo();
                return;
            }

            Player turnPlayer = Bukkit.getPlayer(turnUuid);

            if (currentTurnSeconds <= 0) {
                List<CardInstance> handInstances = playerHands.get(turnUuid);
                List<Card> selected = new ArrayList<>();
                if (handInstances != null) {
                    for (CardInstance ci : handInstances) {
                        if (ci.selected) selected.add(ci.card);
                    }
                }

                if (!selected.isEmpty() && isValidPlay(selected)) {
                    processPlayAction(turnUuid, selected, turnPlayer);
                    return;
                }

                if (currentFieldCards.isEmpty()) {
                    List<Card> hand = playerDataHands.get(turnUuid);
                    if (hand != null && !hand.isEmpty()) {
                        List<Card> autoSelected = new ArrayList<>();
                        autoSelected.add(hand.get(0));
                        processPlayAction(turnUuid, autoSelected, turnPlayer);
                    }
                } else {
                    handlePassAction(turnUuid);
                }
                return;
            }

            if (turnPlayer != null && turnPlayer.isOnline()) {
                NamedTextColor tColor = (currentTurnSeconds <= 5) ? NamedTextColor.RED : NamedTextColor.YELLOW;
                turnPlayer.sendActionBar(Component.text("【 あなたの手番 】 ⏱ 残り: " + currentTurnSeconds + "秒", tColor));

                if (currentTurnSeconds <= 3) {
                    turnPlayer.playSound(turnPlayer.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.8f);
                }
            }

            currentTurnSeconds--;
        }, 0L, 20L);
    }

    void handleButtonClick(Player player, String action) {
        UUID uuid = player.getUniqueId();
        if (action.equals("SURRENDER")) {
            handleSurrender(uuid);
            return;
        }

        if (action.equals("WAITING")) {
            player.sendMessage(Component.text("[手札交換] 他のプレイヤーの選択完了を待機しています...", NamedTextColor.GRAY));
            return;
        }

        if (gameState == GameState.WAITING || gameState == GameState.RESULT) {
            player.sendMessage(Component.text("[大富豪] ゲームはまだ開始されていません。(/daifugo start)", NamedTextColor.RED));
            return;
        }

        if (action.equals("GIVE_EXCHANGE")) {
            handleExchangeGiveAction(uuid);
            return;
        }

        if (action.equals("GIVE_SEVEN")) {
            handleSevenGiveAction(uuid);
            return;
        }

        if (action.equals("DISCARD_TEN")) {
            handleTenDiscardAction(uuid);
            return;
        }

        // 7渡し・10捨て・Qボンバーの処理中に「パス」等を押されると進行が壊れるため止める
        if (waitingForSevenPass || waitingForTenDiscard || waitingForQBomber) {
            player.sendMessage(Component.text("[大富豪] 特殊効果の処理中です。少しお待ちください。", NamedTextColor.YELLOW));
            return;
        }

        if (!pendingExchangeRequired.isEmpty()) {
            player.sendMessage(Component.text("[大富豪] 手札交換フェーズが完了するまでお待ちください。", NamedTextColor.YELLOW));
            return;
        }

        UUID currentTurnUuid = getCurrentTurnUuid();
        if (!uuid.equals(currentTurnUuid)) {
            player.sendMessage(Component.text("[大富豪] あなたの手番ではありません！", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 0.8f);
            return;
        }

        switch (action) {
            case "PLAY" -> handlePlayAction(uuid);
            case "PASS" -> handlePassAction(uuid);
        }
    }

    void handleSurrender(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        long now = System.currentTimeMillis();
        Long lastClick = surrenderConfirmMap.get(uuid);

        if (lastClick == null || now - lastClick > 5000) {
            surrenderConfirmMap.put(uuid, now);
            if (player != null) {
                player.sendMessage(Component.text("[大富豪] 本当に降参しますか？ 5秒以内にもう一度 [ 降参 ] を押すと確定します。", NamedTextColor.RED, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }
        surrenderConfirmMap.remove(uuid);

        if (gameState == GameState.WAITING || gameState == GameState.RESULT) {
            if (player != null) leavePlayer(player);
            return;
        }

        String pName = player != null ? player.getName() : "プレイヤー";
        forfeitPlayer(uuid, Component.text("[大富豪] " + pName + " が降参しました。", NamedTextColor.RED, TextDecoration.BOLD));
    }

    void handlePlayAction(UUID uuid) {
        if (waitingForSevenPass || waitingForTenDiscard || waitingForQBomber) return;
        if (gameState != GameState.PLAYING) return;
        if (!uuid.equals(getCurrentTurnUuid())) return;

        List<CardInstance> handInstances = playerHands.get(uuid);
        if (handInstances == null) return;

        List<Card> selected = new ArrayList<>();
        for (CardInstance ci : handInstances) {
            if (ci.selected) selected.add(ci.card);
        }

        Player player = Bukkit.getPlayer(uuid);

        if (selected.isEmpty()) {
            if (player != null) {
                player.sendMessage(Component.text("[大富豪] 提出するカードを選択してください。", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }

        if (!isValidPlay(selected)) {
            if (player != null) {
                player.sendMessage(Component.text("[大富豪] その組み合わせは出せません！（ルールを確認してください）", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
            return;
        }

        processPlayAction(uuid, selected, player);
    }

    void processPlayAction(UUID uuid, List<Card> selected, Player player) {
        List<Card> currentDataHand = playerDataHands.get(uuid);
        if (currentDataHand == null) return;

        // 反則上がりは「出す前」の強さで判定する（2を4枚出して革命→反則にならない、を防ぐ）
        boolean revolutionBeforePlay = isEffectiveRevolution();
        boolean isFourStopExecuted = waitingForFourStop && fourStopMode > 0;
        boolean isDownNum = checkIsDownNumber(selected);
        boolean isSpaceThree = (ruleSettings.getOrDefault("space_three", true) && jokerCount > 0
                && currentFieldCards.size() == 1 && currentFieldCards.get(0).isJoker()
                && selected.size() == 1 && "♠".equals(selected.get(0).getSuit()) && selected.get(0).getNumber() == 3);

        for (Card c : selected) {
            currentDataHand.remove(c);
        }

        boolean playIsStairs = checkIsStairs(selected);
        boolean isSame = checkIsSameNumber(selected);
        if (!currentFieldCards.isEmpty() && !currentFieldIsStairs && isSame) {
            playIsStairs = false;
        }

        int selectedTotalCount = selected.size();
        int baseNumber = -1;
        for (Card c : selected) {
            if (!c.isJoker()) {
                baseNumber = c.getNumber();
                break;
            }
        }

        // スート縛り・数字縛り・激縛りの判定
        if (suitLockMode >= 1 && selected.size() == currentFieldCards.size() && !currentFieldCards.isEmpty()) {
            Set<String> prevSuits = getCardSuits(currentFieldCards);
            Set<String> nowSuits = getCardSuits(selected);

            int nowStrength = playIsStairs ? getStairsStrength(selected, isEffectiveRevolution())
                    : (isSame && baseNumber != -1 ? new Card("♠", baseNumber).getStrength(isEffectiveRevolution()) : 14);
            int prevStrength = currentFieldIsStairs ? getStairsStrength(currentFieldCards, isEffectiveRevolution())
                    : (checkIsSameNumber(currentFieldCards) && currentFieldCards.stream().anyMatch(c -> !c.isJoker())
                    ? currentFieldCards.stream().filter(c -> !c.isJoker()).findFirst().get().getStrength(isEffectiveRevolution()) : 14);

            boolean isConsecutiveNum = (nowStrength == prevStrength + 1);
            boolean isSuitSame = (!nowSuits.isEmpty() && prevSuits.equals(nowSuits));

            if (suitLockMode == 2 && isConsecutiveNum && isSuitSame && !isGekiLocked) {
                isGekiLocked = true;
                isNumberLocked = true;
                lockedSuits.clear();
                lockedSuits.addAll(nowSuits);
                broadcast(Component.text("[大富豪] ★★★ 激縛り発動！ (" + String.join("", lockedSuits) + " の連続する数字のみ！) ★★★", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.6f);
                }
            } else {
                if (isSuitSame && lockedSuits.isEmpty()) {
                    lockedSuits.addAll(nowSuits);
                    broadcast(Component.text("[大富豪] ★ " + String.join("", lockedSuits) + " しばり が発生しました！ ★", NamedTextColor.GOLD, TextDecoration.BOLD));
                }
                if (isConsecutiveNum && !isNumberLocked) {
                    isNumberLocked = true;
                    broadcast(Component.text("[大富豪] ★ 数字しばり が発生しました！ (直前+1の連番のみ！) ★", NamedTextColor.AQUA, TextDecoration.BOLD));
                    for (UUID u : joinedPlayers) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.8f);
                    }
                }
            }
        }

        // 革命判定
        if (ruleSettings.getOrDefault("stairs_revolution", true) && playIsStairs && selected.size() >= 4) {
            isRevolution = !isRevolution;
            broadcast(Component.text("[大富豪] ★★★ 階段革命が発生しました！強さが逆転します！ ★★★", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.2f);
            }
        } else if (ruleSettings.getOrDefault("revolution", true) && !playIsStairs && selected.size() >= 4) {
            isRevolution = !isRevolution;
            broadcast(Component.text("[大富豪] ★★★ 革命が発生しました！強さが逆転します！ ★★★", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 1.2f);
            }
        }

        boolean enableEffects = !playIsStairs || stairsEffectMode == 2;
        boolean enableEightOnly = playIsStairs && stairsEffectMode == 1;

        if (ruleSettings.getOrDefault("eleven_back", true) && enableEffects && (selected.stream().anyMatch(c -> c.getNumber() == 11))) {
            isElevenBack = true;
            broadcast(Component.text("[大富豪] 11バック発動！ 場が流れるまで強さが逆転します。", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        }

        boolean hasNine = ruleSettings.getOrDefault("nine_reverse", false) && enableEffects
                && (isSame ? baseNumber == 9 : selected.stream().anyMatch(c -> c.getNumber() == 9));
        if (hasNine) {
            int nineCount = isSame ? selectedTotalCount : (int) selected.stream().filter(c -> c.getNumber() == 9).count();
            if (nineCount % 2 != 0) {
                isTurnReversed = !isTurnReversed;
                broadcast(Component.text("[大富豪] 9リバース発動！ 回り順が逆転しました！ (" + (isTurnReversed ? "反時計回り" : "時計回り") + ")", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
                }
            } else {
                broadcast(Component.text("[大富豪] 9が偶数枚出されたため、リバース効果が相殺されました！（回り順維持）", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.0f);
                }
            }
        }

        boolean hasQueen = ruleSettings.getOrDefault("q_bomber", false) && enableEffects && (isSame ? baseNumber == 12 : selected.stream().anyMatch(c -> c.getNumber() == 12));
        int queenCount = hasQueen ? (isSame ? selectedTotalCount : 1) : 0;

        boolean hasFive = ruleSettings.getOrDefault("five_skip", false) && enableEffects && (isSame ? baseNumber == 5 : selected.stream().anyMatch(c -> c.getNumber() == 5));
        int fiveCount = hasFive ? (isSame ? selectedTotalCount : 1) : 0;

        boolean hasEight = ruleSettings.getOrDefault("eight_cut", true) && (enableEffects || enableEightOnly) && (isSame ? baseNumber == 8 : selected.stream().anyMatch(c -> c.getNumber() == 8));
        boolean hasSeven = ruleSettings.getOrDefault("seven_pass", false) && enableEffects && (isSame ? baseNumber == 7 : selected.stream().anyMatch(c -> c.getNumber() == 7));
        int sevenCount = hasSeven ? (isSame ? selectedTotalCount : 1) : 0;

        boolean hasTen = ruleSettings.getOrDefault("ten_discard", false) && enableEffects && (isSame ? baseNumber == 10 : selected.stream().anyMatch(c -> c.getNumber() == 10));
        int tenCount = hasTen ? (isSame ? selectedTotalCount : 1) : 0;

        currentFieldCards.clear();
        currentFieldCards.addAll(selected);
        currentFieldIsStairs = playIsStairs;
        lastPlayPlayerUuid = uuid;
        consecutivePassCount = 0;

        if (!ruleSettings.getOrDefault("pass_lock", false)) {
            passedPlayers.clear();
        }

        Location pLoc = player != null ? player.getLocation() : playerSeatLocations.get(uuid);
        if (pLoc != null) {
            playCardThrowParticles(pLoc, tableCenter.clone().add(0, 1.85, 0));
        }
        updateFieldCards(selected);

        Location seatLoc = playerSeatLocations.get(uuid);
        if (seatLoc != null && player != null && player.isOnline()) {
            renderPlayerHand(uuid, seatLoc);
        }

        String pName = player != null ? player.getName() : "切断中のプレイヤー";
        List<String> labels = selected.stream().map(c -> c.getSuit() + c.getNumber()).toList();
        broadcast(Component.text("[大富豪] ", NamedTextColor.GREEN)
                .append(Component.text(pName + " ", NamedTextColor.WHITE))
                .append(Component.text("が提出: ", NamedTextColor.GRAY))
                .append(Component.text(String.join(", ", labels), NamedTextColor.YELLOW)));

        if (player != null) {
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1.2f);
        }

        if (isDownNum) {
            broadcast(Component.text("[大富豪] ダウンナンバー発動！ " + pName + " が同じスートの1つ下の数字を出しました！", NamedTextColor.AQUA, TextDecoration.BOLD));
        }

        if (currentDataHand.isEmpty()) {
            boolean isFoul = false;
            if (ruleSettings.getOrDefault("illegal_finish", true)) {
                if (revolutionBeforePlay) {
                    isFoul = selected.stream().anyMatch(c -> c.getNumber() == 3 || c.getNumber() == 8 || c.isJoker()) || isSpaceThree;
                } else {
                    isFoul = selected.stream().anyMatch(c -> c.getNumber() == 2 || c.getNumber() == 8 || c.isJoker()) || isSpaceThree;
                }
            }

            if (isFoul) {
                foulPlayers.add(uuid);
                removePlayerUI(uuid);
                broadcast(Component.text("[大富豪] ⚠ " + pName + " は反則カードで上がったため、反則負け（最下位）となりました！ ⚠", NamedTextColor.RED, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.8f, 1.5f);
                }
            } else {
                finishedRankings.add(uuid);
                removePlayerUI(uuid);
                int rank = finishedRankings.size();

                if (ruleSettings.getOrDefault("miyakooti", true) && rank == 1 && previousGameRankings.size() >= 2) {
                    UUID prevDaifugoUuid = previousGameRankings.get(0);
                    if (isJoined(prevDaifugoUuid) && !prevDaifugoUuid.equals(uuid) && !finishedRankings.contains(prevDaifugoUuid)) {
                        foulPlayers.add(prevDaifugoUuid);
                        removePlayerUI(prevDaifugoUuid);
                        Player prevDf = Bukkit.getPlayer(prevDaifugoUuid);
                        String pdfName = prevDf != null ? prevDf.getName() : "前大富豪";
                        broadcast(Component.text("[都落ち] " + pName + " が1位上がりしたため、前大富豪(" + pdfName + ") は都落ち（大貧民）となりました！", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                        for (UUID u : joinedPlayers) {
                            Player p = Bukkit.getPlayer(u);
                            if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1.2f);
                        }
                    }
                }

                broadcast(Component.text("[大富豪] ★ " + pName + " が手札を出し切り " + rank + "位 で上がりました！ ★", NamedTextColor.GOLD, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }
            }

            if (checkGameFinish()) {
                return;
            }
        }

        updateCentralStatus();
        updateAllScoreboards();
        updateAllHeadDisplays();

        if (isFourStopExecuted) {
            resetTrick();
            broadcast(Component.text("[大富豪] 4止め成功！ 8切りが阻止され、場が流れて " + pName + " の手番になります。", NamedTextColor.RED, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.8f, 1.4f);
            }
            broadcastTurnInfo();
            return;
        }

        if (isSpaceThree) {
            resetTrick();
            broadcast(Component.text("[大富豪] スペ3返し！ 場が流れて " + pName + " の手番が継続します。", NamedTextColor.AQUA, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
            }
            broadcastTurnInfo();
            return;
        }

        pendingEffectQueue.clear();
        // 7/10/Qの処理が終わった後も5スキップが消えないように覚えておく
        queuedFiveCount = fiveCount;
        if (hasSeven && !currentDataHand.isEmpty() && getActivePlayerUuids().size() > 1) {
            pendingEffectQueue.add("SEVEN");
            queuedSevenCount = sevenCount;
        }
        if (hasTen && !currentDataHand.isEmpty()) {
            pendingEffectQueue.add("TEN");
            queuedTenCount = tenCount;
        }
        if (hasQueen) {
            pendingEffectQueue.add("QUEEN");
            queuedQueenCount = queenCount;
        }
        if (hasEight) {
            pendingEffectQueue.add("EIGHT");
            eightCardCount = selectedTotalCount;
        }

        // キューが空なら executeNextPendingEffect がそのまま手番送り（5スキップ含む）をする
        executeNextPendingEffect(uuid, fiveCount);
    }

    UUID getNextActivePlayerUuid(UUID current) {
        List<UUID> active = getActivePlayerUuids();
        if (active.isEmpty()) return null;
        int idx = active.indexOf(current);
        if (idx == -1) return active.get(0);
        int step = isTurnReversed ? -1 : 1;
        return active.get((idx + step + active.size()) % active.size());
    }

    void handlePassAction(UUID uuid) {
        if (gameState != GameState.PLAYING) return;
        if (waitingForSevenPass || waitingForTenDiscard || waitingForQBomber) return;
        if (!uuid.equals(getCurrentTurnUuid())) return;

        Player player = Bukkit.getPlayer(uuid);

        if (currentFieldCards.isEmpty()) {
            if (player != null) {
                player.sendMessage(Component.text("[大富豪] 場にカードがないためパスはできません。（親番です）", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }

        if (waitingForFourStop) {
            waitingForFourStop = false;
            resetTrick();

            broadcast(Component.text("[大富豪] 4止めされなかったため、8切りが成立しました！", NamedTextColor.AQUA, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
            }

            if (lastPlayPlayerUuid != null && isJoined(lastPlayPlayerUuid)) {
                currentTurnIndex = joinedPlayers.indexOf(lastPlayPlayerUuid);
            }
            consecutivePassCount = 0;
            broadcastTurnInfo();
            return;
        }

        consecutivePassCount++;
        passedPlayers.add(uuid);

        if (player != null) {
            showPassHologram(player);
            broadcast(Component.text("[大富豪] " + player.getName() + " はパスしました。", NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.8f);
        } else {
            broadcast(Component.text("[大富豪] " + (disconnectedPlayers.containsKey(uuid) ? "切断中のプレイヤー" : "プレイヤー") + " が自動パスしました。", NamedTextColor.GRAY));
        }

        checkFieldClearOrAdvance();
    }

    void checkFieldClearOrAdvance() {
        List<UUID> activeUuids = getActivePlayerUuids();
        if (activeUuids.isEmpty()) {
            checkGameFinish();
            return;
        }

        boolean shouldClear = false;

        if (lastPlayPlayerUuid != null && activeUuids.contains(lastPlayPlayerUuid)) {
            if (consecutivePassCount >= activeUuids.size() - 1) {
                shouldClear = true;
            }
        } else {
            if (consecutivePassCount >= activeUuids.size()) {
                shouldClear = true;
            }
        }

        if (shouldClear) {
            resetTrick();
            broadcast(Component.text("[大富豪] 全員がパスしたため場が流れました。", NamedTextColor.AQUA, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            }

            if (lastPlayPlayerUuid != null && isJoined(lastPlayPlayerUuid)) {
                if (activeUuids.contains(lastPlayPlayerUuid)) {
                    currentTurnIndex = joinedPlayers.indexOf(lastPlayPlayerUuid);
                } else if (!activeUuids.isEmpty()) {
                    advanceTurnIndexOnly();
                }
            } else if (!activeUuids.isEmpty()) {
                advanceTurnIndexOnly();
            }

            updateAllScoreboards();
            broadcastTurnInfo();
            return;
        }

        advanceTurn();
    }

    void advanceTurnIndexOnly() {
        List<UUID> active = getActivePlayerUuids();
        if (active.isEmpty()) return;
        int step = isTurnReversed ? -1 : 1;
        int attempts = 0;
        int maxAttempts = joinedPlayers.size() * 3;

        do {
            currentTurnIndex = (currentTurnIndex + step + joinedPlayers.size()) % joinedPlayers.size();
            attempts++;
            if (attempts > maxAttempts) {
                resetTrick();
                break;
            }
        } while (!isValidTurnPlayer(getCurrentTurnUuid(), active));
    }

    void advanceTurn() {
        advanceTurnIndexOnly();

        List<UUID> active = getActivePlayerUuids();
        if (!currentFieldCards.isEmpty() && lastPlayPlayerUuid != null) {
            UUID nextUuid = getCurrentTurnUuid();
            if (nextUuid.equals(lastPlayPlayerUuid)) {
                resetTrick();
                broadcast(Component.text("[大富豪] 他の全員がスキップ・パスしたため場が流れました。", NamedTextColor.AQUA, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                }
            }
        }

        broadcastTurnInfo();
        updateAllScoreboards();
    }

    boolean checkGameFinish() {
        List<UUID> active = getActivePlayerUuids();

        if ((joinedPlayers.size() == 1 && active.isEmpty()) || (joinedPlayers.size() > 1 && active.size() <= 1)) {
            if (!active.isEmpty()) {
                UUID lastWinner = active.get(0);
                finishedRankings.add(lastWinner);
                removePlayerUI(lastWinner);
            }
            finishedRankings.addAll(foulPlayers);

            showGameResult();
            return true;
        }
        return false;
    }

    void showGameResult() {
        gameState = GameState.RESULT;
        if (turnTimerTask != null) turnTimerTask.cancel();
        cancelPhaseTimer();
        waitingForSevenPass = false;
        waitingForTenDiscard = false;
        waitingForFourStop = false;
        pendingEffectQueue.clear();
        closeQBomberKeypad();
        clearFieldCards();
        updateCentralStatus();
        updateAllScoreboards();
        updateAllHeadDisplays();

        previousGameRankings.clear();
        previousGameRankings.addAll(finishedRankings);

        if (!finishedRankings.isEmpty()) {
            recordGameResult(finishedRankings.get(0));
        }

        int total = finishedRankings.size();
        String[] titles = getRankTitles(total);

        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.sendMessage(Component.text("=================================", NamedTextColor.GOLD, TextDecoration.BOLD));
                p.sendMessage(Component.text("         ★ 大富豪 リザルト ★        ", NamedTextColor.YELLOW, TextDecoration.BOLD));
                for (int i = 0; i < total; i++) {
                    UUID rankedUuid = finishedRankings.get(i);
                    Player rp = Bukkit.getPlayer(rankedUuid);
                    String rpName = rp != null ? rp.getName() : "プレイヤー";
                    String title = (i < titles.length) ? titles[i] : (i + 1) + "位";
                    p.sendMessage(Component.text(" " + (i + 1) + "位: ", NamedTextColor.WHITE)
                            .append(Component.text(rpName + " ", NamedTextColor.YELLOW))
                            .append(Component.text("(" + title + ")", NamedTextColor.GRAY)));
                }
                p.sendMessage(Component.text("=================================", NamedTextColor.GOLD, TextDecoration.BOLD));

                UUID winnerUuid = finishedRankings.get(0);
                Player winner = Bukkit.getPlayer(winnerUuid);
                String wName = winner != null ? winner.getName() : "大富豪";

                p.showTitle(Title.title(
                        Component.text("★ 勝者: " + wName + " ★", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("大富豪に君臨しました！", NamedTextColor.YELLOW),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))
                ));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
        }

        Location fireworkLoc = tableCenter.clone().add(0, 1.5, 0);
        Firework fw = (Firework) fireworkLoc.getWorld().spawnEntity(fireworkLoc, EntityType.FIREWORK_ROCKET);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .withColor(Color.YELLOW, Color.ORANGE, Color.RED)
                .withFade(Color.WHITE)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);

        boolean isContinue = ruleSettings.getOrDefault("continue_game", true);
        final int session = sessionId;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            // 7秒の間に /daifugo clear や卓の作り直しがあった場合は何もしない
            if (session != sessionId || gameState != GameState.RESULT || tableCenter == null) return;
            if (isContinue && !joinedPlayers.isEmpty()) {
                gameState = GameState.WAITING; 
                finishedRankings.clear();
                foulPlayers.clear();
                updateAllHeadDisplays();

                spawnJoinHologram();
                renderHostRuleSettings();
                for (UUID uuid : joinedPlayers) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.sendMessage(Component.text("------------------------------------------------", NamedTextColor.GOLD, TextDecoration.BOLD));
                        p.sendMessage(Component.text("[連戦モード] 次の対戦準備が完了しました！", NamedTextColor.GREEN, TextDecoration.BOLD));
                        p.sendMessage(Component.text("/daifugo start を入力すると次のゲームが始まります。", NamedTextColor.YELLOW));
                        p.sendMessage(Component.text("(終了したい場合は /daifugo clear を実行してください)", NamedTextColor.GRAY));
                        p.sendMessage(Component.text("------------------------------------------------", NamedTextColor.GOLD, TextDecoration.BOLD));
                        p.showTitle(Title.title(
                                Component.text("【 連戦モード 】", NamedTextColor.GREEN, TextDecoration.BOLD),
                                Component.text("/daifugo start で次のゲームを開始", NamedTextColor.YELLOW),
                                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))
                        ));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
                    }
                }
            } else {
                broadcast(Component.text("[大富豪] 対戦が終了し、座席がリセットされました。", NamedTextColor.GREEN));
                clearAll(tableCenter);
            }
        }, 140L);
    }

    String[] getRankTitles(int count) {
        return switch (count) {
            case 2 -> new String[]{"§6大富豪", "§8大貧民"};
            case 3 -> new String[]{"§6大富豪", "§f平民", "§8大貧民"};
            case 4 -> new String[]{"§6大富豪", "§e富豪", "§7貧民", "§8大貧民"};
            case 5 -> new String[]{"§6大富豪", "§e富豪", "§f平民", "§7貧民", "§8大貧民"};
            default -> new String[]{"§6大富豪", "§e富豪", "§f平民", "§f平民", "§7貧民", "§8大貧民"};
        };
    }
}
