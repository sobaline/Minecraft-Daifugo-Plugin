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
 * 特殊効果の担当：7渡し・10捨て・Qボンバー・5スキップ・8切り・手札交換・制限時間
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public abstract class DaifugoEffects extends DaifugoRules {


    void cancelPhaseTimer() {
        if (phaseTimerTask != null) {
            phaseTimerTask.cancel();
            phaseTimerTask = null;
        }
    }

    /**
     * 7渡し・10捨て・Qボンバー・手札交換の制限時間タイマー。
     * 時間切れになると自動で処理し、放置や切断でゲームが止まらないようにする。
     * phase: "SEVEN" / "TEN" / "QUEEN" / "EXCHANGE"
     */
    void startPhaseTimer(UUID uuid, String phase) {
        cancelPhaseTimer();
        phaseSeconds = PHASE_TIME_LIMIT;
        final int session = sessionId;

        phaseTimerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (session != sessionId || (gameState != GameState.PLAYING && gameState != GameState.EXCHANGING)) {
                cancelPhaseTimer();
                return;
            }

            boolean stillWaiting = switch (phase) {
                case "SEVEN" -> waitingForSevenPass && uuid != null && uuid.equals(sevenPassPlayerUuid);
                case "TEN" -> waitingForTenDiscard && uuid != null && uuid.equals(tenDiscardPlayerUuid);
                case "QUEEN" -> waitingForQBomber && uuid != null && uuid.equals(qBomberPlayerUuid);
                case "EXCHANGE" -> gameState == GameState.EXCHANGING && !exchangePairs.isEmpty();
                default -> false;
            };
            if (!stillWaiting) {
                cancelPhaseTimer();
                return;
            }

            if (phaseSeconds <= 0) {
                cancelPhaseTimer();
                autoResolvePhase(uuid, phase);
                return;
            }

            NamedTextColor color = phaseSeconds <= 5 ? NamedTextColor.RED : NamedTextColor.YELLOW;
            Component bar = Component.text("⏱ 残り " + phaseSeconds + "秒（時間切れで自動処理されます）", color);
            if (phase.equals("EXCHANGE")) {
                for (UUID rich : exchangePairs.keySet()) {
                    if (richSelectedCards.containsKey(rich)) continue;
                    Player rp = Bukkit.getPlayer(rich);
                    if (rp != null) rp.sendActionBar(bar);
                }
            } else {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendActionBar(bar);
            }
            phaseSeconds--;
        }, 20L, 20L);
    }

    void autoResolvePhase(UUID uuid, String phase) {
        switch (phase) {
            case "SEVEN" -> {
                List<Card> hand = playerDataHands.get(uuid);
                if (hand == null || hand.isEmpty()) {
                    waitingForSevenPass = false;
                    sevenPassPlayerUuid = null;
                    executeNextPendingEffect(uuid, 0);
                    return;
                }
                broadcast(Component.text("[7渡し] 時間切れのため、弱いカードを自動で渡します。", NamedTextColor.GRAY));
                executeSevenGive(uuid, pickWeakestCards(hand, sevenPassRequiredCount));
            }
            case "TEN" -> {
                List<Card> hand = playerDataHands.get(uuid);
                if (hand == null || hand.isEmpty()) {
                    waitingForTenDiscard = false;
                    tenDiscardPlayerUuid = null;
                    executeNextPendingEffect(uuid, 0);
                    return;
                }
                broadcast(Component.text("[10捨て] 時間切れのため、弱いカードを自動で捨てます。", NamedTextColor.GRAY));
                executeTenDiscard(uuid, pickWeakestCards(hand, tenDiscardRequiredCount));
            }
            case "QUEEN" -> {
                int num = ThreadLocalRandom.current().nextInt(1, 14);
                broadcast(Component.text("[Qボンバー] 時間切れのため、数字をランダムに選びます。", NamedTextColor.GRAY));
                executeQBomber(uuid, num);
            }
            case "EXCHANGE" -> {
                for (UUID rich : new ArrayList<>(exchangePairs.keySet())) {
                    if (richSelectedCards.containsKey(rich)) continue;
                    List<Card> hand = playerDataHands.get(rich);
                    int req = pendingExchangeRequired.getOrDefault(rich, 0);
                    if (hand == null) {
                        exchangePairs.remove(rich);
                        continue;
                    }
                    richSelectedCards.put(rich, pickWeakestCards(hand, req));
                    Player rp = Bukkit.getPlayer(rich);
                    if (rp != null) {
                        rp.sendMessage(Component.text("[手札交換] 時間切れのため、弱いカードを自動で選びました。", NamedTextColor.GRAY));
                    }
                }
                executeAllExchanges();
            }
            default -> { }
        }
    }

    boolean initCardExchangePhase() {
        int total = previousGameRankings.size();
        UUID daifugoUuid = previousGameRankings.get(0);
        UUID daihinminUuid = previousGameRankings.get(total - 1);

        boolean needed = false;
        if (isJoined(daifugoUuid) && isJoined(daihinminUuid)) {
            int count = (total == 3) ? 1 : 2;
            List<Card> daihinminHand = new ArrayList<>(playerDataHands.get(daihinminUuid));
            Collections.sort(daihinminHand);

            List<Card> toGive = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                toGive.add(daihinminHand.get(daihinminHand.size() - 1 - i));
            }
            poorCardsToGive.put(daihinminUuid, toGive);
            exchangePairs.put(daifugoUuid, daihinminUuid);
            pendingExchangeRequired.put(daifugoUuid, count);

            Player daifugo = Bukkit.getPlayer(daifugoUuid);
            Player daihinmin = Bukkit.getPlayer(daihinminUuid);

            List<String> cardNames = toGive.stream().map(Card::getShortLabel).toList();
            if (daihinmin != null) {
                String dfName = daifugo != null ? daifugo.getName() : "大富豪";
                daihinmin.sendMessage(Component.text("[手札交換] あなたは大富豪 (" + dfName + ") へ 【 " + String.join(", ", cardNames) + " 】 を渡します。（大富豪の選択待ち...）", NamedTextColor.YELLOW));
            }
            if (daifugo != null) {
                String dhmName = daihinmin != null ? daihinmin.getName() : "大貧民";
                daifugo.sendMessage(Component.text("[手札交換] 大貧民 (" + dhmName + ") から最強カード " + count + "枚 を受け取ります。不要なカードを " + count + "枚 選択して [ 渡す ] を押してください。", NamedTextColor.YELLOW));
            }
            needed = true;
        }

        if (total >= 4) {
            UUID fugoUuid = previousGameRankings.get(1);
            UUID hinminUuid = previousGameRankings.get(total - 2);
            if (isJoined(fugoUuid) && isJoined(hinminUuid)) {
                int count = 1;
                List<Card> hinminHand = new ArrayList<>(playerDataHands.get(hinminUuid));
                Collections.sort(hinminHand);

                List<Card> toGive = new ArrayList<>();
                toGive.add(hinminHand.get(hinminHand.size() - 1));

                poorCardsToGive.put(hinminUuid, toGive);
                exchangePairs.put(fugoUuid, hinminUuid);
                pendingExchangeRequired.put(fugoUuid, count);

                Player fugo = Bukkit.getPlayer(fugoUuid);
                Player hinmin = Bukkit.getPlayer(hinminUuid);

                List<String> cardNames = toGive.stream().map(Card::getShortLabel).toList();
                if (hinmin != null) {
                    String fgName = fugo != null ? fugo.getName() : "富豪";
                    hinmin.sendMessage(Component.text("[手札交換] あなたは富豪 (" + fgName + ") へ 【 " + String.join(", ", cardNames) + " 】 を渡します。（富豪の選択待ち...）", NamedTextColor.YELLOW));
                }
                if (fugo != null) {
                    String hmName = hinmin != null ? hinmin.getName() : "貧民";
                    fugo.sendMessage(Component.text("[手札交換] 貧民 (" + hmName + ") から最強カード 1枚 を受け取ります。不要なカードを 1枚 選択して [ 渡す ] を押してください。", NamedTextColor.YELLOW));
                }
                needed = true;
            }
        }
        return needed;
    }

    void handleExchangeGiveAction(UUID uuid) {
        if (!pendingExchangeRequired.containsKey(uuid)) return;
        int req = pendingExchangeRequired.get(uuid);

        List<CardInstance> handInstances = playerHands.get(uuid);
        List<Card> currentDataHand = playerDataHands.get(uuid);
        if (handInstances == null || currentDataHand == null) return;

        List<Card> selectedCards = new ArrayList<>();
        for (CardInstance ci : handInstances) {
            if (ci.selected) selectedCards.add(ci.card);
        }

        Player player = Bukkit.getPlayer(uuid);
        if (selectedCards.size() != req) {
            if (player != null) {
                player.sendMessage(Component.text("[手札交換] 渡すカードをちょうど " + req + "枚 選択してください！", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }

        richSelectedCards.put(uuid, selectedCards);
        if (player != null) {
            player.sendMessage(Component.text("[手札交換] カードを選択しました。全員の準備が整うまでお待ちください...", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
        }

        Location pSeat = playerSeatLocations.get(uuid);
        if (pSeat != null) {
            spawnActionButtons(uuid, pSeat);
        }

        if (richSelectedCards.keySet().containsAll(exchangePairs.keySet())) {
            executeAllExchanges();
        }
    }

    void executeAllExchanges() {
        cancelPhaseTimer();
        if (gameState != GameState.EXCHANGING) return;
        for (Map.Entry<UUID, UUID> entry : exchangePairs.entrySet()) {
            UUID richUuid = entry.getKey();
            UUID poorUuid = entry.getValue();

            List<Card> rCards = richSelectedCards.get(richUuid);
            List<Card> pCards = poorCardsToGive.get(poorUuid);

            List<Card> rHand = playerDataHands.get(richUuid);
            List<Card> pHand = playerDataHands.get(poorUuid);

            if (rHand != null && pHand != null && rCards != null && pCards != null) {
                for (Card c : rCards) {
                    rHand.remove(c);
                    pHand.add(c);
                }
                for (Card c : pCards) {
                    pHand.remove(c);
                    rHand.add(c);
                }

                Collections.sort(rHand);
                Collections.sort(pHand);

                Player rich = Bukkit.getPlayer(richUuid);
                Player poor = Bukkit.getPlayer(poorUuid);

                List<String> rLabels = rCards.stream().map(Card::getShortLabel).toList();
                List<String> pLabels = pCards.stream().map(Card::getShortLabel).toList();

                String roleRich = richUuid.equals(previousGameRankings.get(0)) ? "大富豪" : "富豪";
                String rolePoor = poorUuid.equals(previousGameRankings.get(previousGameRankings.size() - 1)) ? "大貧民" : "貧民";

                String rName = rich != null ? rich.getName() : "プレイヤー";
                String pName = poor != null ? poor.getName() : "プレイヤー";

                Component exchangeMsg = Component.text("[手札交換] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text(roleRich + "(" + rName + ") は 【 " + String.join(", ", rLabels) + " 】 を渡し、", NamedTextColor.YELLOW))
                        .append(Component.text(rolePoor + "(" + pName + ") は 【 " + String.join(", ", pLabels) + " 】 を渡しました！", NamedTextColor.RED));

                if (rich != null) rich.sendMessage(exchangeMsg);
                if (poor != null) poor.sendMessage(exchangeMsg);
            }
        }

        pendingExchangeRequired.clear();
        richSelectedCards.clear();
        poorCardsToGive.clear();
        exchangePairs.clear();

        for (UUID uuid : joinedPlayers) {
            Location seat = playerSeatLocations.get(uuid);
            if (seat != null) {
                renderPlayerHand(uuid, seat);
                spawnActionButtons(uuid, seat);
            }
        }

        gameState = GameState.PLAYING;
        broadcast(Component.text("[大富豪] 全員の手札交換が完了しました！ゲームを開始します。", NamedTextColor.GREEN, TextDecoration.BOLD));
        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1.2f);
        }
        broadcastTurnInfo();
    }

    void executeNextPendingEffect(UUID uuid, int fiveCount) {
        if (pendingEffectQueue.isEmpty()) {
            int five = Math.max(fiveCount, queuedFiveCount);
            queuedFiveCount = 0;
            handlePostPlayTurnAdvance(uuid, five);
            return;
        }

        String nextEffect = pendingEffectQueue.remove(0);
        List<Card> currentDataHand = playerDataHands.get(uuid);
        Player player = Bukkit.getPlayer(uuid);

        switch (nextEffect) {
            case "SEVEN" -> {
                if (currentDataHand != null && !currentDataHand.isEmpty() && getActivePlayerUuids().size() > 1) {
                    waitingForSevenPass = true;
                    sevenPassRequiredCount = Math.min(queuedSevenCount, currentDataHand.size());
                    sevenPassPlayerUuid = uuid;
                    if (turnTimerTask != null) turnTimerTask.cancel();
                    startPhaseTimer(uuid, "SEVEN");

                    if (player != null) {
                        player.sendActionBar(Component.text("【 7渡し 】 渡すカードを " + sevenPassRequiredCount + "枚 選択して [ 渡す ] を押してください", NamedTextColor.AQUA, TextDecoration.BOLD));
                    }
                    Location pSeat = playerSeatLocations.get(uuid);
                    if (pSeat != null) spawnActionButtons(uuid, pSeat);
                } else {
                    executeNextPendingEffect(uuid, fiveCount);
                }
            }
            case "TEN" -> {
                if (currentDataHand != null && !currentDataHand.isEmpty()) {
                    waitingForTenDiscard = true;
                    tenDiscardRequiredCount = Math.min(queuedTenCount, currentDataHand.size());
                    tenDiscardPlayerUuid = uuid;
                    if (turnTimerTask != null) turnTimerTask.cancel();
                    startPhaseTimer(uuid, "TEN");

                    if (player != null) {
                        player.sendActionBar(Component.text("【 10捨て 】 捨てるカードを " + tenDiscardRequiredCount + "枚 選択して [ 捨てる ] を押してください", NamedTextColor.RED, TextDecoration.BOLD));
                    }
                    Location pSeat = playerSeatLocations.get(uuid);
                    if (pSeat != null) spawnActionButtons(uuid, pSeat);
                } else {
                    executeNextPendingEffect(uuid, fiveCount);
                }
            }
            case "QUEEN" -> {
                openQBomberKeypad(uuid, queuedQueenCount, fiveCount);
            }
            case "EIGHT" -> {
                queuedFiveCount = 0; // 8切り／4止め待ちでは5スキップは使わない
                if (fourStopMode > 0 && getActivePlayerUuids().size() > 1) {
                    waitingForFourStop = true;
                    int reqFour = (fourStopMode == 1) ? 2 : eightCardCount * 2;
                    broadcast(Component.text("[大富豪] 8が出ました！ 4止め（4を" + reqFour + "枚以上）で阻止できます！", NamedTextColor.YELLOW));
                    advanceTurn();
                } else {
                    resetTrick();
                    String pName = player != null ? player.getName() : "プレイヤー";
                    broadcast(Component.text("[大富豪] 8切り！ 場が流れて " + pName + " の手番が継続します。", NamedTextColor.AQUA, TextDecoration.BOLD));
                    for (UUID u : joinedPlayers) {
                        Player p = Bukkit.getPlayer(u);
                        if (p != null) p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.4f);
                    }
                    broadcastTurnInfo();
                }
            }
            default -> executeNextPendingEffect(uuid, fiveCount);
        }
    }

    void handlePostPlayTurnAdvance(UUID uuid, int fiveCount) {
        if (fiveCount > 0) {
            // 5の枚数ぶん人を飛ばす＝(枚数+1)人先に進む。以前は「枚数×2」で、5を2枚出すと3人飛んでいた
            int skipSteps = fiveCount + 1;
            for (int i = 0; i < skipSteps; i++) {
                advanceTurnIndexOnly();
            }

            UUID nextUuid = getCurrentTurnUuid();
            if (nextUuid.equals(uuid)) {
                resetTrick();
                Player p = Bukkit.getPlayer(uuid);
                String pName = p != null ? p.getName() : "プレイヤー";
                broadcast(Component.text("[大富豪] 5スキップで全員がスキップされ、場が流れて " + pName + " の手番になります！", NamedTextColor.AQUA, TextDecoration.BOLD));
                for (UUID u : joinedPlayers) {
                    Player pl = Bukkit.getPlayer(u);
                    if (pl != null) pl.playSound(pl.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                }
                broadcastTurnInfo();
                return;
            }

            int skippedPeople = skipSteps - 1;
            broadcast(Component.text("[大富豪] 5スキップ！ 手番が " + skippedPeople + "人スキップされました。", NamedTextColor.YELLOW));
            broadcastTurnInfo();
            updateAllScoreboards();
            return;
        }

        advanceTurn();
    }

    void handleTenDiscardAction(UUID uuid) {
        if (!waitingForTenDiscard || !uuid.equals(tenDiscardPlayerUuid)) return;

        List<CardInstance> handInstances = playerHands.get(uuid);
        List<Card> currentDataHand = playerDataHands.get(uuid);
        if (handInstances == null || currentDataHand == null) return;

        List<Card> selectedToDiscard = new ArrayList<>();
        for (CardInstance ci : handInstances) {
            if (ci.selected) selectedToDiscard.add(ci.card);
        }

        Player player = Bukkit.getPlayer(uuid);
        if (selectedToDiscard.size() != tenDiscardRequiredCount) {
            if (player != null) {
                player.sendMessage(Component.text("[10捨て] 捨てるカードをちょうど " + tenDiscardRequiredCount + "枚 選択してください！", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }

        executeTenDiscard(uuid, selectedToDiscard);
    }

    /** 10捨ての実処理（手動・時間切れ自動の両方から呼ばれる） */
    void executeTenDiscard(UUID uuid, List<Card> selectedToDiscard) {
        if (!waitingForTenDiscard || !uuid.equals(tenDiscardPlayerUuid)) return;
        cancelPhaseTimer();
        List<Card> currentDataHand = playerDataHands.get(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (currentDataHand == null) {
            waitingForTenDiscard = false;
            tenDiscardPlayerUuid = null;
            executeNextPendingEffect(uuid, 0);
            return;
        }

        for (Card c : selectedToDiscard) {
            currentDataHand.remove(c);
        }

        waitingForTenDiscard = false;
        tenDiscardPlayerUuid = null;

        Location seatLoc = playerSeatLocations.get(uuid);
        if (seatLoc != null && player != null && player.isOnline()) {
            renderPlayerHand(uuid, seatLoc);
            spawnActionButtons(uuid, seatLoc);
        }

        String pName = player != null ? player.getName() : "プレイヤー";
        broadcast(Component.text("[大富豪] 10捨て！ " + pName + " が手札から " + selectedToDiscard.size() + "枚 を破棄しました。", NamedTextColor.YELLOW));
        for (UUID u : joinedPlayers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.2f);
        }

        updateAllScoreboards();
        updateAllHeadDisplays();

        if (currentDataHand.isEmpty()) {
            finishedRankings.add(uuid);
            removePlayerUI(uuid);
            int rank = finishedRankings.size();
            broadcast(Component.text("[大富豪] ★ " + pName + " が10捨てにより " + rank + "位 で上がりました！ ★", NamedTextColor.GOLD, TextDecoration.BOLD));
            if (checkGameFinish()) return;
        }

        executeNextPendingEffect(uuid, 0);
    }

    void openQBomberKeypad(UUID uuid, int pickCount, int fiveCount) {
        closeQBomberKeypad();
        waitingForQBomber = true;
        qBomberRemainingPicks = pickCount;
        qBomberPlayerUuid = uuid;
        if (turnTimerTask != null) turnTimerTask.cancel();

        renderQBomberKeypadUI(uuid, fiveCount);
        startPhaseTimer(uuid, "QUEEN");
    }

    void executeQBomber(UUID uuid, int targetNumber) {
        // 二重クリックや本人以外の操作で何度も爆破されないようにする
        if (!waitingForQBomber || !uuid.equals(qBomberPlayerUuid)) return;
        cancelPhaseTimer();
        String label = switch (targetNumber) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> String.valueOf(targetNumber);
        };

        Player player = Bukkit.getPlayer(uuid);
        String pName = player != null ? player.getName() : "プレイヤー";

        broadcast(Component.text("[Qボンバー] " + pName + " が数字 [ " + label + " ] を爆破指定！ 全員の手札からすべて破棄されました！", NamedTextColor.RED, TextDecoration.BOLD));
        for (UUID u : joinedPlayers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        }

        for (UUID u : joinedPlayers) {
            List<Card> h = playerDataHands.get(u);
            if (h != null) {
                h.removeIf(c -> c.getNumber() == targetNumber);
                Location sLoc = playerSeatLocations.get(u);
                Player hp = Bukkit.getPlayer(u);
                boolean stillPlaying = !finishedRankings.contains(u) && !foulPlayers.contains(u);
                // オフラインの人や上がった人の席に手札が浮かないようにする
                if (sLoc != null && hp != null && hp.isOnline() && stillPlaying) renderPlayerHand(u, sLoc);

                if (h.isEmpty() && !finishedRankings.contains(u) && !foulPlayers.contains(u)) {
                    finishedRankings.add(u);
                    removePlayerUI(u);
                    int rank = finishedRankings.size();
                    Player targetP = Bukkit.getPlayer(u);
                    String tpName = targetP != null ? targetP.getName() : "プレイヤー";
                    broadcast(Component.text("[大富豪] ★ " + tpName + " がQボンバーの爆破により " + rank + "位 で上がりました！ ★", NamedTextColor.GOLD, TextDecoration.BOLD));
                }
            }
        }

        updateAllScoreboards();
        updateAllHeadDisplays();

        if (checkGameFinish()) {
            closeQBomberKeypad();
            return;
        }

        qBomberRemainingPicks--;
        if (qBomberRemainingPicks > 0) {
            renderQBomberKeypadUI(uuid, 0);
            startPhaseTimer(uuid, "QUEEN");
        } else {
            closeQBomberKeypad();
            executeNextPendingEffect(uuid, 0);
        }
    }

    void handleSevenGiveAction(UUID uuid) {
        if (!waitingForSevenPass || !uuid.equals(sevenPassPlayerUuid)) return;

        List<CardInstance> handInstances = playerHands.get(uuid);
        List<Card> currentDataHand = playerDataHands.get(uuid);
        if (handInstances == null || currentDataHand == null) return;

        List<Card> selectedToGive = new ArrayList<>();
        for (CardInstance ci : handInstances) {
            if (ci.selected) selectedToGive.add(ci.card);
        }

        Player player = Bukkit.getPlayer(uuid);
        if (selectedToGive.size() != sevenPassRequiredCount) {
            if (player != null) {
                player.sendMessage(Component.text("[7渡し] 渡すカードをちょうど " + sevenPassRequiredCount + "枚 選択してください！", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 1f);
            }
            return;
        }

        executeSevenGive(uuid, selectedToGive);
    }

    /** 7渡しの実処理（手動・時間切れ自動の両方から呼ばれる） */
    void executeSevenGive(UUID uuid, List<Card> selectedToGive) {
        if (!waitingForSevenPass || !uuid.equals(sevenPassPlayerUuid)) return;
        cancelPhaseTimer();
        List<Card> currentDataHand = playerDataHands.get(uuid);
        Player player = Bukkit.getPlayer(uuid);

        UUID nextUuid = getNextActivePlayerUuid(uuid);
        if (nextUuid == null || nextUuid.equals(uuid) || currentDataHand == null) {
            // 渡す相手がいない場合は7渡しを飛ばして進行を続ける
            waitingForSevenPass = false;
            sevenPassPlayerUuid = null;
            executeNextPendingEffect(uuid, 0);
            return;
        }

        List<Card> targetHand = playerDataHands.get(nextUuid);
        if (targetHand != null) {
            for (Card c : selectedToGive) {
                currentDataHand.remove(c);
                targetHand.add(c);
            }
            Collections.sort(targetHand);
        }

        if (disconnectedPlayers.containsKey(nextUuid)) {
            DisconnectData dData = disconnectedPlayers.get(nextUuid);
            if (dData != null && dData.hand != null) {
                for (Card c : selectedToGive) {
                    if (!dData.hand.contains(c)) {
                        dData.hand.add(c);
                    }
                }
                Collections.sort(dData.hand);
            }
        }

        waitingForSevenPass = false;
        sevenPassPlayerUuid = null;

        Location seatLoc = playerSeatLocations.get(uuid);
        if (seatLoc != null && player != null && player.isOnline()) {
            renderPlayerHand(uuid, seatLoc);
            spawnActionButtons(uuid, seatLoc);
        }

        Player nextTarget = Bukkit.getPlayer(nextUuid);
        if (nextTarget != null && nextTarget.isOnline()) {
            Location targetSeat = playerSeatLocations.get(nextUuid);
            if (targetSeat != null) {
                renderPlayerHand(nextUuid, targetSeat);
            }
            nextTarget.playSound(nextTarget.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1.2f);
        }

        String pName = player != null ? player.getName() : "プレイヤー";
        String nName = nextTarget != null ? nextTarget.getName() : "プレイヤー";

        List<String> givenCardNames = selectedToGive.stream().map(Card::getShortLabel).toList();
        for (UUID u : joinedPlayers) {
            if (!u.equals(nextUuid)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    p.sendMessage(Component.text("[大富豪] 7渡し！ " + pName + " から " + nName + " へカードが " + selectedToGive.size() + "枚渡されました。", NamedTextColor.AQUA));
                }
            }
        }
        if (nextTarget != null) {
            nextTarget.sendMessage(Component.text("[7渡し] " + pName + " からカードを受け取りました: 【 " + String.join(", ", givenCardNames) + " 】", NamedTextColor.AQUA, TextDecoration.BOLD));
        }

        updateAllScoreboards();
        updateAllHeadDisplays();

        if (currentDataHand.isEmpty()) {
            finishedRankings.add(uuid);
            removePlayerUI(uuid);
            int rank = finishedRankings.size();
            broadcast(Component.text("[大富豪] ★ " + pName + " が7渡しによって手札を出し切り " + rank + "位 で上がりました！ ★", NamedTextColor.GOLD, TextDecoration.BOLD));
            for (UUID u : joinedPlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (checkGameFinish()) return;
        }

        executeNextPendingEffect(uuid, 0);
    }
}
