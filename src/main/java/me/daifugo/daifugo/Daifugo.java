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
 * プラグイン本体：起動/停止、コマンド、マイクラのイベント（クリック・切断など）
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public final class Daifugo extends DaifugoGame {


    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
        } catch (Exception ignored) {}

        loadRuleConfig();
        loadStatsConfig();
        getServer().getPluginManager().registerEvents(this, this);

        cleanupWorldEntities();

        disconnectCheckTask = Bukkit.getScheduler().runTaskTimer(this, this::checkDisconnectTimeouts, 20L, 20L);
        particleTask = Bukkit.getScheduler().runTaskTimer(this, this::playRevolutionParticles, 10L, 10L);

        getLogger().info("大富豪プラグインが完全版として有効化されました！");
    }

    @Override
    public void onDisable() {
        clearAll(null);
        cleanupWorldEntities();
        if (disconnectCheckTask != null) disconnectCheckTask.cancel();
        if (particleTask != null) particleTask.cancel();
        if (turnTimerTask != null) turnTimerTask.cancel();
        cancelPhaseTimer();
        getLogger().info("大富豪プラグインが無効化されました。");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("daifugo")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("プレイヤーのみ実行可能です。", NamedTextColor.RED));
            return true;
        }

        boolean isAdmin = player.hasPermission(ADMIN_PERMISSION);
        boolean isHost = !joinedPlayers.isEmpty() && joinedPlayers.get(0).equals(player.getUniqueId());

        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("help")) {
                sendHelp(player);
                return true;
            }
            if (args[0].equalsIgnoreCase("stats")) {
                String path = player.getUniqueId().toString();
                int plays = statsConfig.getInt(path + ".plays", 0);
                int wins = statsConfig.getInt(path + ".wins", 0);
                int streak = statsConfig.getInt(path + ".streak", 0);
                double rate = plays > 0 ? (double) wins / plays * 100 : 0;

                player.sendMessage(Component.text("=== あなたの戦績 ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                player.sendMessage(Component.text("プレイ回数: ", NamedTextColor.WHITE).append(Component.text(plays + "回", NamedTextColor.GREEN)));
                player.sendMessage(Component.text("大富豪獲得: ", NamedTextColor.WHITE).append(Component.text(wins + "回", NamedTextColor.GREEN)));
                player.sendMessage(Component.text("勝率: ", NamedTextColor.WHITE).append(Component.text(String.format("%.1f", rate) + "%", NamedTextColor.GREEN)));
                player.sendMessage(Component.text("現在の連勝: ", NamedTextColor.WHITE).append(Component.text(streak + "連勝", NamedTextColor.GREEN)));
                return true;
            }
            if (args[0].equalsIgnoreCase("clear")) {
                boolean hostCanClear = isHost && (gameState == GameState.WAITING || gameState == GameState.RESULT);
                if (!isAdmin && !hostCanClear) {
                    player.sendMessage(Component.text("[大富豪] 対戦中の卓を消せるのは管理者(OP)だけです。（ホストは待機中のみ消せます）", NamedTextColor.RED));
                    return true;
                }
                previousGameRankings.clear();
                clearAll(player.getLocation());
                player.sendMessage(Component.text("[大富豪] すべて消去し、連戦データをリセットしました。", NamedTextColor.YELLOW));
                return true;
            }
            if (args[0].equalsIgnoreCase("start")) {
                if (gameState != GameState.WAITING) {
                    player.sendMessage(Component.text("[大富豪] すでにゲームは進行中または準備中です。", NamedTextColor.RED));
                    return true;
                }
                if (!isAdmin && !isHost) {
                    player.sendMessage(Component.text("[大富豪] 対戦を開始できるのはホスト（最初の参加者）か管理者だけです。", NamedTextColor.RED));
                    return true;
                }
                // オフラインのまま残っている参加者を外してから開始する
                removeOfflinePlayers();
                if (joinedPlayers.isEmpty()) {
                    player.sendMessage(Component.text("[大富豪] 参加者がいません。", NamedTextColor.RED));
                    return true;
                }
                // 立ち上がっていた人も含めて座り直させる
                rearrangeTable();
                startGame();
                return true;
            }

            // 知らないサブコマンド（打ち間違い）で卓がリセットされないようにする
            player.sendMessage(Component.text("[大富豪] 不明なサブコマンドです: " + args[0], NamedTextColor.RED));
            sendHelp(player);
            return true;
        }

        boolean canCreate = tableCenter == null || isAdmin || (gameState == GameState.WAITING && joinedPlayers.isEmpty());
        if (!canCreate) {
            player.sendMessage(Component.text("[大富豪] すでに卓があります。作り直せるのは管理者(OP)か、参加者0人のときだけです。", NamedTextColor.RED));
            return true;
        }

        clearAll(player.getLocation());
        tableCenter = player.getLocation().clone();
        tableCenter.setPitch(0.0f);
        tableCenter.setYaw(0.0f);

        spawnJoinHologram();
        player.sendMessage(Component.text("[大富豪] 参加募集を開始しました！中央ホログラム右クリックで参加/退出、/daifugo start で開始します。", NamedTextColor.GREEN));
        player.sendMessage(Component.text("※ /daifugo stats で自分の戦績を確認できます。", NamedTextColor.GRAY));
        return true;
    }

    // ============================================
    // ▼ 修正版で追加した補助メソッド ▼
    // ============================================

    void sendHelp(Player player) {
        player.sendMessage(Component.text("=== 大富豪 コマンド ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("/daifugo : その場に卓を作る", NamedTextColor.WHITE));
        player.sendMessage(Component.text("/daifugo start : 対戦開始（ホスト/管理者）", NamedTextColor.WHITE));
        player.sendMessage(Component.text("/daifugo stats : 自分の戦績", NamedTextColor.WHITE));
        player.sendMessage(Component.text("/daifugo clear : 卓を消す（管理者、または待機中のホスト）", NamedTextColor.WHITE));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (String s : List.of("start", "stats", "clear", "help")) {
                if (s.startsWith(args[0].toLowerCase())) result.add(s);
            }
            return result;
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isJoined(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            UUID uuid = player.getUniqueId();
            // プラグイン自身が降ろす処理は止めない
            if (allowDismount.contains(uuid)) return;
            // 上がった人・棄権した人は立ち上がって観戦してよい（手札は本人にしか見えない）
            if (finishedRankings.contains(uuid) || foulPlayers.contains(uuid)) return;
            if (isJoined(uuid) && (gameState != GameState.WAITING || tableCenter != null)) {
                event.setCancelled(true);
            }
        }
    }

    // ============================================
    // ▼ ワープ・アイテム使用での脱獄・悪用防止処理 ▼
    // ============================================

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (isJoined(player.getUniqueId()) && (gameState != GameState.WAITING || tableCenter != null)) {
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL ||
                event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
                event.setCancelled(true);
                player.sendMessage(Component.text("[大富豪] プレイ中のワープはできません！", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isJoined(player.getUniqueId()) && (gameState != GameState.WAITING || tableCenter != null)) {
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                if (event.getItem() != null) {
                    Material type = event.getItem().getType();
                    if (type == Material.ENDER_PEARL || type == Material.CHORUS_FRUIT) {
                        event.setCancelled(true);
                        player.sendMessage(Component.text("[大富豪] プレイ中はそのアイテムを使えません！", NamedTextColor.RED));
                    }
                }
            }
        }
    }

    // ============================================

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!isJoined(uuid)) return;

        safelyDismountPlayer(uuid);

        if (gameState == GameState.WAITING || gameState == GameState.RESULT) {
            leavePlayer(player);
            return;
        }

        List<Card> currentHand = playerDataHands.get(uuid);
        Location currentSeat = playerSeatLocations.get(uuid);

        DisconnectData data = new DisconnectData(
                currentHand != null ? currentHand : new ArrayList<>(),
                currentSeat,
                System.currentTimeMillis()
        );
        disconnectedPlayers.put(uuid, data);

        removePlayerUI(uuid);

        broadcast(Component.text("[大富豪] ⚠ " + player.getName() + " が切断しました（復帰猶予: 60秒） ⚠", NamedTextColor.RED, TextDecoration.BOLD));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (disconnectedPlayers.containsKey(uuid)) {
            DisconnectData data = disconnectedPlayers.remove(uuid);

            Collections.sort(data.hand);
            playerDataHands.put(uuid, data.hand);
            playerSeatLocations.put(uuid, data.seat);

            final int session = sessionId;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) return;
                if (session != sessionId || tableCenter == null || !isJoined(uuid)) return;

                if (data.seat != null) {
                    player.teleport(data.seat);
                    seatPlayer(player, data.seat);
                }

                if ((gameState == GameState.PLAYING || gameState == GameState.EXCHANGING) && !finishedRankings.contains(uuid) && !foulPlayers.contains(uuid)) {
                    Collections.sort(playerDataHands.get(uuid));
                    renderPlayerHand(uuid, data.seat);
                    spawnActionButtons(uuid, data.seat);
                }
                if (data.seat != null) {
                    spawnHeadDisplay(uuid, data.seat);
                }

                broadcast(Component.text("[大富豪] ✦ " + player.getName() + " がゲームに復帰しました！ ✦", NamedTextColor.GREEN, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);

                updateAllScoreboards();
                updateAllHeadDisplays();

                if (gameState == GameState.PLAYING && !joinedPlayers.isEmpty()) {
                    UUID currentTurnUuid = getCurrentTurnUuid();
                    if (uuid.equals(currentTurnUuid)) {
                        broadcastTurnInfo();
                    }
                }
            }, 3L);
        }
    }

    @EventHandler
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Entity clicked = event.getRightClicked();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (waitingForQBomber && qBomberActionMap.containsKey(clicked.getUniqueId())) {
            event.setCancelled(true);
            // Qボンバーを出した本人以外は押せないようにする
            if (!uuid.equals(qBomberPlayerUuid)) {
                player.sendMessage(Component.text("[Qボンバー] 数字を選べるのはQを出したプレイヤーだけです。", NamedTextColor.RED));
                return;
            }
            int selectedNumber = qBomberActionMap.get(clicked.getUniqueId());
            executeQBomber(uuid, selectedNumber);
            return;
        }

        if (ruleToggleActions.containsKey(clicked.getUniqueId())) {
            event.setCancelled(true);
            if (joinedPlayers.isEmpty() || !uuid.equals(joinedPlayers.get(0))) {
                player.sendMessage(Component.text("[大富豪] ルール設定は最初に参加したホストのみ変更できます。", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO, 1f, 0.8f);
                return;
            }

            String ruleKey = ruleToggleActions.get(clicked.getUniqueId());
            if (ruleKey.equals("joker_count")) {
                jokerCount = (jokerCount + 1) % 3;
                saveRuleSetting("joker_count", jokerCount);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.4f);
                broadcast(Component.text("[大富豪] ホストがジョーカー枚数を変更: " + jokerCount + "枚", NamedTextColor.YELLOW));
            } else if (ruleKey.equals("suit_lock_mode")) {
                suitLockMode = (suitLockMode + 1) % 3;
                saveRuleSetting("suit_lock_mode", suitLockMode);
                String sModeName = switch (suitLockMode) {
                    case 0 -> "なし [OFF]";
                    case 1 -> "通常縛り [ON]";
                    default -> "激縛り [GEKI]";
                };
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.4f);
                broadcast(Component.text("[大富豪] ホストが縛りルールを変更: " + sModeName, NamedTextColor.YELLOW));
            } else if (ruleKey.equals("stairs_effect_mode")) {
                stairsEffectMode = (stairsEffectMode + 1) % 3;
                saveRuleSetting("stairs_effect_mode", stairsEffectMode);
                String stName = switch (stairsEffectMode) {
                    case 0 -> "なし [OFF]";
                    case 1 -> "8切りのみ [ON]";
                    default -> "全て発動 [ON]";
                };
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.4f);
                broadcast(Component.text("[大富豪] ホストが階段役重複ルールを変更: " + stName, NamedTextColor.YELLOW));
            } else if (ruleKey.equals("four_stop_mode")) {
                fourStopMode = (fourStopMode + 1) % 3;
                saveRuleSetting("four_stop_mode", fourStopMode);
                String fModeName = switch (fourStopMode) {
                    case 0 -> "なし [OFF]";
                    case 1 -> "2枚固定 [ON]";
                    default -> "倍数(x2) [ON]";
                };
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.4f);
                broadcast(Component.text("[大富豪] ホストが4止めルールを変更: " + fModeName, NamedTextColor.YELLOW));
            } else {
                boolean currentVal = ruleSettings.getOrDefault(ruleKey, true);
                ruleSettings.put(ruleKey, !currentVal);
                saveRuleSetting(ruleKey, !currentVal);
                String statusStr = !currentVal ? "有効 [ON]" : "無効 [OFF]";
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.9f, 1.4f);
                broadcast(Component.text("[大富豪] ホストがルールを変更: " + getRuleNameJa(ruleKey) + " → " + statusStr, NamedTextColor.YELLOW));
            }

            renderHostRuleSettings();
            return;
        }

        if (gameState == GameState.WAITING && joinInteraction != null && clicked.getUniqueId().equals(joinInteraction.getUniqueId())) {
            event.setCancelled(true);
            if (isJoined(uuid)) {
                leavePlayer(player);
            } else {
                joinPlayer(player);
            }
            return;
        }

        if (buttonActions.containsKey(clicked.getUniqueId())) {
            event.setCancelled(true);
            // 他人の席のボタン（特に「降参」）を押せないようにする
            List<Entity> myButtons = playerButtonEntities.get(uuid);
            boolean isMine = myButtons != null && myButtons.stream().anyMatch(e -> e.getUniqueId().equals(clicked.getUniqueId()));
            if (!isMine) {
                player.sendMessage(Component.text("[大富豪] それは他のプレイヤーのボタンです。", NamedTextColor.RED));
                return;
            }
            String action = buttonActions.get(clicked.getUniqueId());
            handleButtonClick(player, action);
            return;
        }

        List<CardInstance> hand = playerHands.get(uuid);
        if (hand != null) {
            for (CardInstance ci : hand) {
                if (ci.interaction.getUniqueId().equals(clicked.getUniqueId())) {
                    event.setCancelled(true);
                    toggleCardSelection(player, ci);
                    return;
                }
            }
        }
    }
}
