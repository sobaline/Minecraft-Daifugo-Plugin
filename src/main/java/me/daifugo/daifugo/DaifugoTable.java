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
 * 見た目の担当：卓・手札・ボタン・ホログラム・スコアボードの表示と後片付け
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public abstract class DaifugoTable extends DaifugoBase {


    void cleanupWorldEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(ENTITY_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    void removeHeadDisplay(UUID uuid) {
        TextDisplay td = playerHeadDisplays.remove(uuid);
        if (td != null) {
            if (td.isValid()) td.remove();
            allEntities.remove(td);
        }
    }

    void spawnJoinHologram() {
        // 連戦モードで古いホログラムが残らないように、先に消しておく
        if (joinDisplay != null) {
            if (joinDisplay.isValid()) joinDisplay.remove();
            allEntities.remove(joinDisplay);
        }
        if (joinInteraction != null) {
            if (joinInteraction.isValid()) joinInteraction.remove();
            allEntities.remove(joinInteraction);
        }
        Location loc = tableCenter.clone().add(0, 1.2, 0);
        loc.setPitch(0.0f);

        joinDisplay = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        joinDisplay.addScoreboardTag(ENTITY_TAG);
        joinDisplay.setBillboard(Display.Billboard.CENTER);
        joinDisplay.setBackgroundColor(Color.fromARGB(220, 20, 20, 20));
        allEntities.add(joinDisplay);

        joinInteraction = (Interaction) loc.getWorld().spawnEntity(loc.clone().subtract(0, 0.2, 0), EntityType.INTERACTION);
        joinInteraction.addScoreboardTag(ENTITY_TAG);
        joinInteraction.setInteractionWidth(1.4f);
        joinInteraction.setInteractionHeight(0.8f);
        allEntities.add(joinInteraction);

        updateJoinHologramText();
    }

    void updateJoinHologramText() {
        if (joinDisplay == null || !joinDisplay.isValid()) return;
        UUID hostUuid = joinedPlayers.isEmpty() ? null : joinedPlayers.get(0);
        Player host = hostUuid != null ? Bukkit.getPlayer(hostUuid) : null;
        String hostName = (host != null) ? "§6ホスト: §e" + host.getName() + "\n" : "";

        String text = "§6§l【大富豪 参加募集中】\n"
                + hostName
                + "§e右クリックで 参加 / 退出\n"
                + "§f参加人数: §a" + joinedPlayers.size() + "§7 / " + MAX_PLAYERS + "人\n"
                + "§7(/daifugo start で対戦開始)";
        joinDisplay.text(Component.text(text));
    }

    void renderHostRuleSettings() {
        for (Entity e : hostSettingEntities) {
            if (e.isValid()) e.remove();
            allEntities.remove(e);
        }
        hostSettingEntities.clear();
        ruleToggleActions.clear();

        if (joinedPlayers.isEmpty() || gameState != GameState.WAITING) return;

        UUID hostUuid = joinedPlayers.get(0);
        Location hostSeat = playerSeatLocations.get(hostUuid);
        if (hostSeat == null) return;

        Vector dirToCenter = tableCenter.toVector().subtract(hostSeat.toVector()).setY(0).normalize();
        Vector right = new Vector(-dirToCenter.getZ(), 0, dirToCenter.getX()).normalize();

        Location panelCenter = hostSeat.clone().add(dirToCenter.clone().multiply(1.3)).add(0, 1.45, 0);
        panelCenter.setDirection(dirToCenter.clone().multiply(-1));

        Location titleLoc = panelCenter.clone().add(0, 0.72, 0);
        TextDisplay titleDisplay = (TextDisplay) titleLoc.getWorld().spawnEntity(titleLoc, EntityType.TEXT_DISPLAY);
        titleDisplay.addScoreboardTag(ENTITY_TAG);
        titleDisplay.setBillboard(Display.Billboard.FIXED);
        titleDisplay.setBackgroundColor(Color.fromARGB(230, 25, 25, 25));
        titleDisplay.text(Component.text("§6§l【 ルール設定 (ホスト専用) 】 §7クリックで切替"));
        hostSettingEntities.add(titleDisplay);
        allEntities.add(titleDisplay);

        List<String> settingKeys = List.of(
                "joker_count", "suit_lock_mode", "revolution", "stairs", "stairs_revolution",
                "stairs_effect_mode", "eight_cut", "four_stop_mode", "eleven_back", "space_three",
                "illegal_finish", "five_skip", "seven_pass", "nine_reverse", "ten_discard",
                "q_bomber", "down_number", "pass_lock", "continue_game", "card_exchange", "miyakooti"
        );

        for (int i = 0; i < settingKeys.size(); i++) {
            String key = settingKeys.get(i);
            int row = i / 5;
            int col = i % 5;

            double offsetX = (col - 2.0) * 0.68;
            double offsetY = 0.35 - (row * 0.32);

            Location btnLoc = panelCenter.clone().add(right.clone().multiply(offsetX)).add(0, offsetY, 0);

            TextDisplay btnDisplay = (TextDisplay) btnLoc.getWorld().spawnEntity(btnLoc, EntityType.TEXT_DISPLAY);
            btnDisplay.addScoreboardTag(ENTITY_TAG);
            btnDisplay.setBillboard(Display.Billboard.FIXED);

            if (key.equals("joker_count")) {
                String statusStr = switch (jokerCount) {
                    case 0 -> "§c0枚";
                    case 1 -> "§a1枚";
                    default -> "§d2枚";
                };
                Color jBg = switch (jokerCount) {
                    case 0 -> Color.fromARGB(220, 80, 25, 25);
                    case 1 -> Color.fromARGB(220, 25, 80, 25);
                    default -> Color.fromARGB(220, 90, 25, 90);
                };
                btnDisplay.setBackgroundColor(jBg);
                btnDisplay.text(Component.text("ジョーカー\n[" + statusStr + "]"));
            } else if (key.equals("suit_lock_mode")) {
                String statusStr = switch (suitLockMode) {
                    case 0 -> "§cなし";
                    case 1 -> "§a通常";
                    default -> "§d激縛り";
                };
                Color sBg = switch (suitLockMode) {
                    case 0 -> Color.fromARGB(220, 80, 25, 25);
                    case 1 -> Color.fromARGB(220, 25, 80, 25);
                    default -> Color.fromARGB(220, 90, 25, 90);
                };
                btnDisplay.setBackgroundColor(sBg);
                btnDisplay.text(Component.text("しばり\n[" + statusStr + "]"));
            } else if (key.equals("stairs_effect_mode")) {
                String stStr = switch (stairsEffectMode) {
                    case 0 -> "§cなし";
                    case 1 -> "§a8切りのみ";
                    default -> "§d全て発動";
                };
                Color stBg = switch (stairsEffectMode) {
                    case 0 -> Color.fromARGB(220, 80, 25, 25);
                    case 1 -> Color.fromARGB(220, 25, 80, 25);
                    default -> Color.fromARGB(220, 90, 25, 90);
                };
                btnDisplay.setBackgroundColor(stBg);
                btnDisplay.text(Component.text("階段役重複\n[" + stStr + "]"));
            } else if (key.equals("four_stop_mode")) {
                String statusStr = switch (fourStopMode) {
                    case 0 -> "§cなし";
                    case 1 -> "§a2枚固定";
                    default -> "§d倍数(x2)";
                };
                Color fBg = switch (fourStopMode) {
                    case 0 -> Color.fromARGB(220, 80, 25, 25);
                    case 1 -> Color.fromARGB(220, 25, 80, 25);
                    default -> Color.fromARGB(220, 90, 25, 90);
                };
                btnDisplay.setBackgroundColor(fBg);
                btnDisplay.text(Component.text("4止め\n[" + statusStr + "]"));
            } else {
                boolean enabled = ruleSettings.getOrDefault(key, false);
                String statusTag = enabled ? "§a[ON]" : "§c[OFF]";
                Color bgColor = enabled ? Color.fromARGB(220, 25, 80, 25) : Color.fromARGB(220, 80, 25, 25);
                btnDisplay.setBackgroundColor(bgColor);
                btnDisplay.text(Component.text(getRuleNameJa(key) + "\n" + statusTag));
            }

            btnDisplay.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.58f, 0.65f, 1.0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));

            Interaction btnInteraction = (Interaction) btnLoc.getWorld().spawnEntity(btnLoc.clone().subtract(0, 0.05, 0), EntityType.INTERACTION);
            btnInteraction.addScoreboardTag(ENTITY_TAG);
            btnInteraction.setInteractionWidth(0.24f);
            btnInteraction.setInteractionHeight(0.24f);

            ruleToggleActions.put(btnInteraction.getUniqueId(), key);

            hostSettingEntities.add(btnDisplay);
            hostSettingEntities.add(btnInteraction);
            allEntities.add(btnDisplay);
            allEntities.add(btnInteraction);
        }
    }

    void removeHostRuleSettings() {
        for (Entity e : hostSettingEntities) {
            if (e.isValid()) e.remove();
            allEntities.remove(e);
        }
        hostSettingEntities.clear();
        ruleToggleActions.clear();
    }

    void seatPlayer(Player player, Location seatLoc) {
        safelyDismountPlayer(player.getUniqueId());

        // マーカー化による沈み込みを相殺する高さ調整 (+0.4)
        Location standLoc = seatLoc.clone().add(0, 0.4, 0);
        ArmorStand seat = (ArmorStand) standLoc.getWorld().spawnEntity(standLoc, EntityType.ARMOR_STAND);
        seat.addScoreboardTag(ENTITY_TAG);
        seat.setVisible(false);
        seat.setGravity(false);
        seat.setMarker(true);
        seat.setSmall(true);
        seat.setInvulnerable(true);

        seat.addPassenger(player);
        playerSeatEntities.put(player.getUniqueId(), seat);
        allEntities.add(seat);
    }

    void safelyDismountPlayer(UUID uuid) {
        ArmorStand seat = playerSeatEntities.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        Location originalSeatLoc = playerSeatLocations.get(uuid);

        allowDismount.add(uuid);
        try {
            if (player != null && player.isOnline()) {
                player.leaveVehicle();
                player.setFallDistance(0.0f);

                if (originalSeatLoc != null) {
                    Location safeLoc = originalSeatLoc.clone().add(0, 0.1, 0);
                    player.teleport(safeLoc);
                }
            }

            if (seat != null && seat.isValid()) {
                seat.eject();
                seat.remove();
                allEntities.remove(seat);
            }
        } finally {
            allowDismount.remove(uuid);
        }
    }

    void rearrangeTable() {
        int count = joinedPlayers.size();
        if (count == 0) return;

        double angleStep = (2 * Math.PI) / count;

        for (int i = 0; i < count; i++) {
            UUID uuid = joinedPlayers.get(i);
            Player p = Bukkit.getPlayer(uuid);
            double angle = i * angleStep;

            double x = tableCenter.getX() + (TABLE_RADIUS * Math.cos(angle));
            double z = tableCenter.getZ() + (TABLE_RADIUS * Math.sin(angle));
            Location seatLoc = new Location(tableCenter.getWorld(), x, tableCenter.getY(), z);

            Vector dirToCenter = tableCenter.toVector().subtract(seatLoc.toVector()).setY(0).normalize();
            seatLoc.setDirection(dirToCenter);
            seatLoc.setPitch(0.0f);

            playerSeatLocations.put(uuid, seatLoc.clone());

            if (p != null && p.isOnline()) {
                p.teleport(seatLoc);
                seatPlayer(p, seatLoc);
            }

            if ((gameState == GameState.PLAYING || gameState == GameState.EXCHANGING) && !finishedRankings.contains(uuid) && !foulPlayers.contains(uuid)) {
                renderPlayerHand(uuid, seatLoc);
                spawnActionButtons(uuid, seatLoc);
            }
        }
    }

    void spawnHeadDisplay(UUID uuid, Location seatLoc) {
        TextDisplay old = playerHeadDisplays.remove(uuid);
        if (old != null && old.isValid()) {
            old.remove();
            allEntities.remove(old);
        }

        Location headLoc = seatLoc.clone().add(0, 2.2, 0);
        TextDisplay headDisplay = (TextDisplay) headLoc.getWorld().spawnEntity(headLoc, EntityType.TEXT_DISPLAY);
        headDisplay.addScoreboardTag(ENTITY_TAG);
        headDisplay.setBillboard(Display.Billboard.CENTER);
        headDisplay.setBackgroundColor(Color.fromARGB(180, 20, 20, 20));

        playerHeadDisplays.put(uuid, headDisplay);
        allEntities.add(headDisplay);
    }

    void updateAllHeadDisplays() {
        for (UUID uuid : joinedPlayers) {
            TextDisplay td = playerHeadDisplays.get(uuid);
            if (td == null || !td.isValid()) continue;

            Player p = Bukkit.getPlayer(uuid);
            String pName = (p != null) ? p.getName() : "プレイヤー";

            if (finishedRankings.contains(uuid)) {
                int rank = finishedRankings.indexOf(uuid) + 1;
                td.text(Component.text(pName + "\n", NamedTextColor.GOLD).append(Component.text("上がり！ (" + rank + "位)", NamedTextColor.GREEN, TextDecoration.BOLD)));
                continue;
            }
            if (foulPlayers.contains(uuid)) {
                td.text(Component.text(pName + "\n", NamedTextColor.GOLD).append(Component.text("反則負け (最下位)", NamedTextColor.RED, TextDecoration.BOLD)));
                continue;
            }

            int count = playerDataHands.getOrDefault(uuid, Collections.emptyList()).size();
            String passTag = (passedPlayers.contains(uuid)) ? " [パス中]" : "";
            if (count <= 2 && count > 0) {
                td.text(Component.text(pName + "\n", NamedTextColor.GOLD)
                        .append(Component.text("残り: " + count + "枚！ (WARNING)" + passTag, NamedTextColor.RED, TextDecoration.BOLD)));
            } else {
                td.text(Component.text(pName + "\n残り: " + count + "枚" + passTag, NamedTextColor.WHITE));
            }
        }
    }

    void updateAllScoreboards() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        UUID turnUuid = joinedPlayers.isEmpty() ? null : getCurrentTurnUuid();

        String fieldCardsStr;
        if (currentFieldCards == null || currentFieldCards.isEmpty()) {
            fieldCardsStr = "§7なし";
        } else {
            List<String> labels = currentFieldCards.stream().map(Card::getShortLabel).toList();
            fieldCardsStr = "§e" + String.join(" ", labels) + (currentFieldIsStairs ? " §d(階段)" : "");
        }

        for (UUID viewerUuid : joinedPlayers) {
            Player viewer = Bukkit.getPlayer(viewerUuid);
            if (viewer == null || !viewer.isOnline()) continue;

            Scoreboard board = mgr.getNewScoreboard();
            Objective obj = board.registerNewObjective("daifugo_sb", Criteria.DUMMY, Component.text("=== 【 大富豪 】 ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);

            try {
                obj.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
            } catch (Throwable ignored) {}

            int score = 20;
            obj.getScore("§7------------------").setScore(score--);

            for (UUID targetUuid : joinedPlayers) {
                Player target = Bukkit.getPlayer(targetUuid);
                String nameStr = targetUuid.equals(viewerUuid) ? "§aあなた" : "§f" + (target != null ? target.getName() : "プレイヤー");
                String line;

                if (finishedRankings.contains(targetUuid)) {
                    int rank = finishedRankings.indexOf(targetUuid) + 1;
                    line = "  " + nameStr + "§7: §a[" + rank + "位 上がり]";
                } else if (foulPlayers.contains(targetUuid)) {
                    line = "  " + nameStr + "§7: §c[反則負け]";
                } else {
                    int count = playerDataHands.getOrDefault(targetUuid, Collections.emptyList()).size();
                    String prefix = (targetUuid.equals(turnUuid)) ? "§e▶ " : "  ";
                    String warning = (count <= 2 && count > 0) ? " §c⚠" : "";
                    String pTag = (passedPlayers.contains(targetUuid)) ? " §8[P]" : "";
                    line = prefix + nameStr + "§7: §e" + count + "枚" + warning + pTag;
                }
                obj.getScore(line).setScore(score--);
            }

            obj.getScore("§7------------------ ").setScore(score--);
            obj.getScore("§f場札: " + fieldCardsStr).setScore(score--);

            String lockStr = "§7なし";
            if (isGekiLocked) {
                lockStr = "§d§l激縛り (" + String.join("", lockedSuits) + ")";
            } else if (!lockedSuits.isEmpty() && isNumberLocked) {
                lockStr = "§6" + String.join("", lockedSuits) + "・数字縛り";
            } else if (!lockedSuits.isEmpty()) {
                lockStr = "§e" + String.join("", lockedSuits) + " 縛り";
            } else if (isNumberLocked) {
                lockStr = "§b数字しばり (連番)";
            }
            obj.getScore("§f縛り: " + lockStr).setScore(score--);

            obj.getScore("§f革命: " + (isRevolution ? "§c§lON" : "§7OFF")).setScore(score--);
            obj.getScore("§f11バック: " + (isElevenBack ? "§d§lON" : "§7OFF")).setScore(score--);
            obj.getScore("§f強度: " + (isEffectiveRevolution() ? "§c§l逆転中 (3最強)" : "§a通常 (2最強)")).setScore(score--);

            if (isTurnReversed) {
                obj.getScore("§d回転: §d反時計回り").setScore(score--);
            }

            viewer.setScoreboard(board);
        }
    }

    void resetScoreboard(Player player) {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr != null) {
            player.setScoreboard(mgr.getMainScoreboard());
        }
    }

    void updateCentralStatus() {
        if (fieldStatusDisplay == null || !fieldStatusDisplay.isValid()) {
            Location statusLoc = tableCenter.clone().add(0, 3.5, 0);
            fieldStatusDisplay = (TextDisplay) statusLoc.getWorld().spawnEntity(statusLoc, EntityType.TEXT_DISPLAY);
            fieldStatusDisplay.addScoreboardTag(ENTITY_TAG);
            fieldStatusDisplay.setBillboard(Display.Billboard.CENTER);
            fieldStatusDisplay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            allEntities.add(fieldStatusDisplay);
        }

        List<String> statuses = new ArrayList<>();
        if (isRevolution && isElevenBack) {
            statuses.add("§e§l【 革命＋11バック中 (強さ相殺・通常) 】");
        } else {
            if (isRevolution) statuses.add("§4§l【 革命中 (REVOLUTION) 】");
            if (isElevenBack) statuses.add("§d§l【 11バック発動中 】");
        }
        if (isTurnReversed) statuses.add("§d§l【 9リバース (逆回転) 】");
        if (isGekiLocked) statuses.add("§5§l【 ★激縛り中★ (" + String.join("", lockedSuits) + ") 】");
        else if (!lockedSuits.isEmpty()) statuses.add("§6§l【 " + String.join("", lockedSuits) + " しばり中 】");
        if (isNumberLocked && !isGekiLocked) statuses.add("§b§l【 数字しばり中 (連番限定) 】");
        if (currentFieldIsStairs) statuses.add("§b§l【 階段出し 】");

        if (statuses.isEmpty()) {
            fieldStatusDisplay.text(Component.empty());
        } else {
            fieldStatusDisplay.text(Component.text(String.join("\n", statuses)));
        }
    }

    void playRevolutionParticles() {
        if (gameState == GameState.WAITING || tableCenter == null) return;
        if (isRevolution || isElevenBack) {
            Location center = tableCenter.clone().add(0, 1.7, 0);
            center.getWorld().spawnParticle(Particle.PORTAL, center, 8, 0.6, 0.4, 0.6, 0.1);
            center.getWorld().spawnParticle(Particle.DRAGON_BREATH, center, 3, 0.4, 0.2, 0.4, 0.02);
        }
    }

    void renderPlayerHand(UUID uuid, Location seatLoc) {
        List<CardInstance> oldHands = playerHands.remove(uuid);
        if (oldHands != null) {
            for (CardInstance ci : oldHands) {
                if (ci.display.isValid()) ci.display.remove();
                if (ci.backDisplay.isValid()) ci.backDisplay.remove();
                if (ci.interaction.isValid()) ci.interaction.remove();
                allEntities.remove(ci.display);
                allEntities.remove(ci.backDisplay);
                allEntities.remove(ci.interaction);
            }
        }

        List<Card> cards = playerDataHands.get(uuid);
        if (cards == null || cards.isEmpty()) return;

        List<CardInstance> handInstances = new ArrayList<>();
        Vector dir = seatLoc.getDirection().setY(0).normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();

        int total = cards.size();

        for (int i = 0; i < total; i++) {
            int row = i / CARDS_PER_ROW;
            int col = i % CARDS_PER_ROW;

            int cardsInThisRow = Math.min(CARDS_PER_ROW, total - (row * CARDS_PER_ROW));
            double startOffset = -((cardsInThisRow - 1) * CARD_SPACING_X) / 2.0;

            double offsetX = startOffset + (col * CARD_SPACING_X);
            double offsetY = 0.85 + (row * CARD_SPACING_Y);
            double offsetZ = i * 0.005; 

            Location cardLoc = seatLoc.clone()
                    .add(dir.clone().multiply(1.3 - offsetZ))
                    .add(right.clone().multiply(offsetX))
                    .add(0, offsetY, 0);

            cardLoc.setDirection(dir.clone().multiply(-1));
            cardLoc.setPitch(0.0f);

            TextDisplay cardDisplay = (TextDisplay) cardLoc.getWorld().spawnEntity(cardLoc, EntityType.TEXT_DISPLAY);
            cardDisplay.addScoreboardTag(ENTITY_TAG);

            Card c = cards.get(i);
            String cardText = c.getDisplayLabel();
            cardDisplay.text(Component.text(cardText, c.getSuitColor()).decorate(TextDecoration.BOLD));
            cardDisplay.setBackgroundColor(Color.fromARGB(255, 255, 255, 255));
            cardDisplay.setBillboard(Display.Billboard.FIXED);

            // 手札の表面は持ち主にしか見えないようにする（隣の席や観戦者からの覗き見防止）
            cardDisplay.setVisibleByDefault(false);
            Player owner = Bukkit.getPlayer(uuid);
            if (owner != null && owner.isOnline()) {
                owner.showEntity(this, cardDisplay);
            }

            cardDisplay.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(1.0f, 1.4f, 1.0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));

            Location backLoc = cardLoc.clone().add(dir.clone().multiply(0.005));
            backLoc.setDirection(dir);

            TextDisplay backDisplay = (TextDisplay) backLoc.getWorld().spawnEntity(backLoc, EntityType.TEXT_DISPLAY);
            backDisplay.addScoreboardTag(ENTITY_TAG);
            backDisplay.setBillboard(Display.Billboard.FIXED);
            backDisplay.setBackgroundColor(Color.fromARGB(255, 20, 20, 30));
            backDisplay.text(Component.text("§c❖§6❖\n§6❖§c❖"));

            backDisplay.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.72f, 1.30f, 1.0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));

            Interaction cardInteraction = (Interaction) cardLoc.getWorld().spawnEntity(
                    cardLoc.clone().add(0, 0.06, 0), EntityType.INTERACTION);
            cardInteraction.addScoreboardTag(ENTITY_TAG);
            cardInteraction.setInteractionWidth(0.18f); 
            cardInteraction.setInteractionHeight(0.55f);

            CardInstance cardInstance = new CardInstance(c, cardDisplay, backDisplay, cardInteraction);
            handInstances.add(cardInstance);

            allEntities.add(cardDisplay);
            allEntities.add(backDisplay);
            allEntities.add(cardInteraction);
        }

        playerHands.put(uuid, handInstances);
    }

    void spawnActionButtons(UUID uuid, Location seatLoc) {
        List<Entity> oldButtons = playerButtonEntities.remove(uuid);
        if (oldButtons != null) {
            for (Entity e : oldButtons) {
                buttonActions.remove(e.getUniqueId());
                if (e.isValid()) e.remove();
                allEntities.remove(e);
            }
        }

        List<Entity> buttonEntities = new ArrayList<>();
        Vector dir = seatLoc.getDirection().setY(0).normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();

        boolean isExchangeWaiting = richSelectedCards.containsKey(uuid);
        boolean isExchange = pendingExchangeRequired.containsKey(uuid) && !isExchangeWaiting;
        boolean isSeven = (waitingForSevenPass && uuid.equals(sevenPassPlayerUuid));
        boolean isTen = (waitingForTenDiscard && uuid.equals(tenDiscardPlayerUuid));

        String playLabel = " §a§l提出 ";
        String playAction = "PLAY";
        Color playColor = Color.fromARGB(220, 25, 90, 25);

        if (isExchangeWaiting) {
            playLabel = " §7§l待機中... ";
            playAction = "WAITING";
            playColor = Color.fromARGB(220, 70, 70, 70);
        } else if (isExchange || isSeven) {
            playLabel = " §b§l渡す ";
            playAction = isExchange ? "GIVE_EXCHANGE" : "GIVE_SEVEN";
            playColor = Color.fromARGB(220, 25, 90, 110);
        } else if (isTen) {
            playLabel = " §c§l捨てる ";
            playAction = "DISCARD_TEN";
            playColor = Color.fromARGB(220, 110, 50, 25);
        }

        // B案: ボタンを大型化しつつ、奥行きを薄くするため「細いInteractionを横に3つ並べる」アプローチ
        createLargeThinButton(buttonEntities, seatLoc, dir, right, -0.70, 0.35, playLabel, playColor, playAction);
        createLargeThinButton(buttonEntities, seatLoc, dir, right, 0.70, 0.35, " §e§lパス ", Color.fromARGB(220, 110, 90, 15), "PASS");
        createLargeThinButton(buttonEntities, seatLoc, dir, right, 2.10, 0.35, " §c§l降参 ", Color.fromARGB(220, 100, 25, 25), "SURRENDER");

        playerButtonEntities.put(uuid, buttonEntities);
    }

    // ★新規メソッド: 大型化＆薄型化ボタン生成 (B案)
    void createLargeThinButton(List<Entity> container, Location seatLoc, Vector dir, Vector right, double offsetX, double offsetY, String label, Color bg, String action) {
        // 見た目 (TextDisplay) の配置
        Location btnLoc = seatLoc.clone()
                .add(dir.clone().multiply(1.2)) // 手札より少し手前に配置
                .add(right.clone().multiply(offsetX))
                .add(0, offsetY, 0);

        btnLoc.setDirection(dir.clone().multiply(-1));
        btnLoc.setPitch(0.0f);

        TextDisplay btnDisplay = (TextDisplay) btnLoc.getWorld().spawnEntity(btnLoc, EntityType.TEXT_DISPLAY);
        btnDisplay.addScoreboardTag(ENTITY_TAG);
        btnDisplay.text(Component.text(label));
        btnDisplay.setBackgroundColor(bg);
        btnDisplay.setBillboard(Display.Billboard.FIXED);

        // Scaleを上げてボタンの見た目を大きくする
        btnDisplay.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(0f, 0f, 0f, 1f),
                new Vector3f(1.8f, 1.8f, 1.0f), // 前回の1.1 -> 1.8に拡大
                new AxisAngle4f(0f, 0f, 0f, 1f)
        ));
        
        container.add(btnDisplay);
        allEntities.add(btnDisplay);

        // 薄い当たり判定 (Interaction) を横に3つ並べる
        double[] hitOffsets = {-0.3, 0.0, 0.3}; 
        for (double hitOffset : hitOffsets) {
            Location hitLoc = btnLoc.clone().add(right.clone().multiply(hitOffset)).add(0, 0.05, 0);
            Interaction btnInteraction = (Interaction) hitLoc.getWorld().spawnEntity(hitLoc, EntityType.INTERACTION);
            btnInteraction.addScoreboardTag(ENTITY_TAG);
            
            // 幅を0.25に絞ることで、奥行きも0.25の「薄い板のパーツ」になる
            btnInteraction.setInteractionWidth(0.25f);
            btnInteraction.setInteractionHeight(0.35f);

            buttonActions.put(btnInteraction.getUniqueId(), action);
            container.add(btnInteraction);
            allEntities.add(btnInteraction);
        }
    }

    void toggleCardSelection(Player player, CardInstance ci) {
        ci.selected = !ci.selected;

        String text = ci.card.getDisplayLabel();
        NamedTextColor color = ci.card.getSuitColor();

        if (ci.selected) {
            ci.display.setBackgroundColor(Color.fromARGB(255, 255, 240, 90));
            ci.display.text(Component.text(text, color).decorate(TextDecoration.BOLD));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
        } else {
            ci.display.setBackgroundColor(Color.fromARGB(255, 255, 255, 255));
            ci.display.text(Component.text(text, color));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.0f);
        }
    }

    void playCardThrowParticles(Location from, Location to) {
        Vector step = to.toVector().subtract(from.toVector()).multiply(0.1);
        Location cur = from.clone().add(0, 1.2, 0);
        for (int i = 0; i < 10; i++) {
            cur.add(step);
            cur.getWorld().spawnParticle(Particle.CRIT, cur, 2, 0.05, 0.05, 0.05, 0.02);
        }
    }

    void renderQBomberKeypadUI(UUID uuid, int fiveCount) {
        for (Entity e : qBomberEntities) {
            if (e.isValid()) e.remove();
            allEntities.remove(e);
        }
        qBomberEntities.clear();
        qBomberActionMap.clear();

        Location seatLoc = playerSeatLocations.get(uuid);
        if (seatLoc == null) return;

        Vector dir = seatLoc.getDirection().setY(0).normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX()).normalize();

        Location padCenter = seatLoc.clone().add(dir.clone().multiply(1.05)).add(0, 1.85, 0);
        padCenter.setDirection(dir.clone().multiply(-1));

        Location tLoc = padCenter.clone().add(0, 0.45, 0);
        TextDisplay tDisplay = (TextDisplay) tLoc.getWorld().spawnEntity(tLoc, EntityType.TEXT_DISPLAY);
        tDisplay.addScoreboardTag(ENTITY_TAG);
        tDisplay.setBillboard(Display.Billboard.FIXED);
        tDisplay.setBackgroundColor(Color.fromARGB(240, 150, 25, 25));
        tDisplay.text(Component.text("§c§l【 Qボンバー 】 爆破する数字をクリック！ (残り " + qBomberRemainingPicks + "回)"));
        qBomberEntities.add(tDisplay);
        allEntities.add(tDisplay);

        int[] numOrder = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 1, 2};
        for (int i = 0; i < numOrder.length; i++) {
            int num = numOrder[i];
            int row = i / 7;
            int col = i % 7;

            double offsetX = (col - 3.0) * 0.42;
            double offsetY = 0.15 - (row * 0.36);

            Location btnLoc = padCenter.clone().add(right.clone().multiply(offsetX)).add(0, offsetY, 0);

            TextDisplay bDisp = (TextDisplay) btnLoc.getWorld().spawnEntity(btnLoc, EntityType.TEXT_DISPLAY);
            bDisp.addScoreboardTag(ENTITY_TAG);
            bDisp.setBillboard(Display.Billboard.FIXED);
            bDisp.setBackgroundColor(Color.fromARGB(220, 30, 30, 30));

            String label = switch (num) {
                case 1 -> " A";
                case 11 -> " J";
                case 12 -> " Q";
                case 13 -> " K";
                case 10 -> "10";
                default -> " " + num;
            };
            bDisp.text(Component.text(label, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

            Interaction bInter = (Interaction) btnLoc.getWorld().spawnEntity(btnLoc.clone(), EntityType.INTERACTION);
            bInter.addScoreboardTag(ENTITY_TAG);
            bInter.setInteractionWidth(0.24f);
            bInter.setInteractionHeight(0.30f);

            qBomberActionMap.put(bInter.getUniqueId(), num);

            qBomberEntities.add(bDisp);
            qBomberEntities.add(bInter);
            allEntities.add(bDisp);
            allEntities.add(bInter);
        }

        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            p.sendActionBar(Component.text("【 Qボンバー 】 爆破する数字をクリック！ (残り " + qBomberRemainingPicks + "回)", NamedTextColor.RED, TextDecoration.BOLD));
        }
    }

    void closeQBomberKeypad() {
        for (Entity e : qBomberEntities) {
            if (e.isValid()) e.remove();
            allEntities.remove(e);
        }
        qBomberEntities.clear();
        qBomberActionMap.clear();
        waitingForQBomber = false;
        qBomberPlayerUuid = null;
    }

    void showPassHologram(Player player) {
        Location loc = player.getLocation().clone().add(0, 2.6, 0);
        TextDisplay passDisp = (TextDisplay) loc.getWorld().spawnEntity(loc, EntityType.TEXT_DISPLAY);
        passDisp.addScoreboardTag(ENTITY_TAG);
        passDisp.setBillboard(Display.Billboard.CENTER);
        passDisp.setBackgroundColor(Color.fromARGB(200, 50, 50, 50));
        passDisp.text(Component.text("[ PASS ]", NamedTextColor.GRAY, TextDecoration.BOLD));
        allEntities.add(passDisp);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (passDisp.isValid()) passDisp.remove();
            allEntities.remove(passDisp);
        }, 16L);
    }

    void clearFieldCards() {
        removeFieldDisplays();
        currentFieldCards.clear();
        currentFieldIsStairs = false;
    }

    void removeFieldDisplays() {
        for (TextDisplay td : fieldDisplays) {
            if (td.isValid()) td.remove();
            allEntities.remove(td);
        }
        fieldDisplays.clear();
    }

    void updateFieldCards(List<Card> cards) {
        removeFieldDisplays();

        int total = cards.size();
        Location fieldCenter = tableCenter.clone().add(0, 1.85, 0);
        fieldCenter.setPitch(0.0f);
        fieldCenter.setYaw(0.0f);

        double spacingX = 1.55;
        double startOffset = -((total - 1) * spacingX) / 2.0;

        for (int i = 0; i < total; i++) {
            float offsetX = (float) (startOffset + (i * spacingX));

            TextDisplay cardDisplay = (TextDisplay) fieldCenter.getWorld().spawnEntity(fieldCenter, EntityType.TEXT_DISPLAY);
            cardDisplay.addScoreboardTag(ENTITY_TAG);
            cardDisplay.setBillboard(Display.Billboard.VERTICAL);
            cardDisplay.setBackgroundColor(Color.fromARGB(255, 255, 255, 255));

            Card c = cards.get(i);
            String cardText = c.getDisplayLabel();
            cardDisplay.text(Component.text(cardText, c.getSuitColor()).decorate(TextDecoration.BOLD));

            cardDisplay.setInterpolationDuration(6);
            cardDisplay.setInterpolationDelay(0);
            cardDisplay.setTransformation(new Transformation(
                    new Vector3f(offsetX, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(3.0f, 4.0f, 1.0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));

            fieldDisplays.add(cardDisplay);
            allEntities.add(cardDisplay);
        }
    }

    void removePlayerUI(UUID uuid) {
        List<CardInstance> hands = playerHands.remove(uuid);
        if (hands != null) {
            for (CardInstance ci : hands) {
                if (ci.display.isValid()) ci.display.remove();
                if (ci.backDisplay.isValid()) ci.backDisplay.remove();
                if (ci.interaction.isValid()) ci.interaction.remove();
                allEntities.remove(ci.display);
                allEntities.remove(ci.backDisplay);
                allEntities.remove(ci.interaction);
            }
        }

        List<Entity> buttons = playerButtonEntities.remove(uuid);
        if (buttons != null) {
            for (Entity e : buttons) {
                buttonActions.remove(e.getUniqueId());
                if (e.isValid()) e.remove();
                allEntities.remove(e);
            }
        }
    }

    void clearAll(Location searchLoc) {
        sessionId++;
        cancelPhaseTimer();
        queuedFiveCount = 0;
        previousGameRankings.clear();

        for (UUID uuid : joinedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) resetScoreboard(p);
            safelyDismountPlayer(uuid);
        }
        playerSeatEntities.clear();

        removeHostRuleSettings();
        closeQBomberKeypad();

        if (turnTimerTask != null) turnTimerTask.cancel();

        for (Entity e : allEntities) {
            if (e.isValid()) e.remove();
        }
        allEntities.clear();
        buttonActions.clear();
        playerHands.clear();
        playerDataHands.clear();
        playerSeatLocations.clear();
        playerButtonEntities.clear();
        playerHeadDisplays.clear();
        joinedPlayers.clear();
        fieldDisplays.clear();
        currentFieldCards.clear();
        currentFieldIsStairs = false;
        finishedRankings.clear();
        foulPlayers.clear();
        passedPlayers.clear();
        surrenderConfirmMap.clear();
        pendingEffectQueue.clear();
        pendingExchangeRequired.clear();
        exchangePairs.clear();
        richSelectedCards.clear();
        poorCardsToGive.clear();
        disconnectedPlayers.clear();
        gameState = GameState.WAITING;
        currentTurnIndex = 0;
        isTurnReversed = false;
        consecutivePassCount = 0;
        lastPlayPlayerUuid = null;
        isRevolution = false;
        isElevenBack = false;
        lockedSuits.clear();
        isGekiLocked = false;
        isNumberLocked = false;
        waitingForFourStop = false;
        eightCardCount = 0;
        fieldStatusDisplay = null;
        waitingForSevenPass = false;
        waitingForTenDiscard = false;
        waitingForQBomber = false;
        qBomberRemainingPicks = 0;
        qBomberPlayerUuid = null;

        cleanupWorldEntities();
        tableCenter = null;
    }
}
