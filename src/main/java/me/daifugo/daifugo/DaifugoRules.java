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
 * ルール判定の担当：出せる組み合わせか、階段か、強さの比較など
 *
 * ファイルの並び（上から順に積み重なっている）:
 * DaifugoBase → DaifugoTable → DaifugoRules → DaifugoEffects → DaifugoGame → Daifugo
 */
public abstract class DaifugoRules extends DaifugoTable {


    /** 今の強さで弱い順に count 枚選ぶ（ジョーカーは最後） */
    List<Card> pickWeakestCards(List<Card> hand, int count) {
        boolean rev = isEffectiveRevolution();
        List<Card> sorted = new ArrayList<>(hand);
        sorted.sort(Comparator.comparingInt(c -> c.getStrength(rev)));
        return new ArrayList<>(sorted.subList(0, Math.min(Math.max(count, 0), sorted.size())));
    }

    boolean isEffectiveRevolution() {
        return isRevolution ^ isElevenBack;
    }

    boolean checkIsStairs(List<Card> cards) {
        if (!ruleSettings.getOrDefault("stairs", true)) return false;
        if (cards == null || cards.size() < 3) return false;

        List<Card> nonJokers = new ArrayList<>();
        for (Card c : cards) {
            if (c != null && !c.isJoker()) nonJokers.add(c);
        }

        if (nonJokers.size() < 2) return false;

        String targetSuit = nonJokers.get(0).getSuit();
        for (Card c : nonJokers) {
            if (!Objects.equals(c.getSuit(), targetSuit)) {
                return false;
            }
        }

        Collections.sort(nonJokers);

        for (int i = 0; i < nonJokers.size() - 1; i++) {
            if (nonJokers.get(i).getNormalStrength() == nonJokers.get(i + 1).getNormalStrength()) {
                return false;
            }
        }

        int min = nonJokers.get(0).getNormalStrength();
        int max = nonJokers.get(nonJokers.size() - 1).getNormalStrength();
        int L = cards.size();

        if (max - min + 1 > L) return false;

        int minPossibleStart = Math.max(1, max - L + 1);
        int maxPossibleStart = Math.min(min, 13 - L + 1);

        return minPossibleStart <= maxPossibleStart;
    }

    int getStairsStrength(List<Card> cards, boolean effectiveRevolution) {
        List<Card> nonJokers = new ArrayList<>();
        for (Card c : cards) {
            if (c != null && !c.isJoker()) nonJokers.add(c);
        }
        Collections.sort(nonJokers);

        int min = nonJokers.get(0).getNormalStrength();
        int max = nonJokers.get(nonJokers.size() - 1).getNormalStrength();
        int L = cards.size();

        int minPossibleStart = Math.max(1, max - L + 1);
        int maxPossibleStart = Math.min(min, 13 - L + 1);

        if (effectiveRevolution) {
            return 14 - minPossibleStart;
        } else {
            return maxPossibleStart + L - 1;
        }
    }

    boolean checkIsSameNumber(List<Card> cards) {
        int nonJokerNumber = -1;
        for (Card c : cards) {
            if (c != null && !c.isJoker()) {
                if (nonJokerNumber == -1) {
                    nonJokerNumber = c.getNumber();
                } else if (nonJokerNumber != c.getNumber()) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean checkIsDownNumber(List<Card> selected) {
        if (!ruleSettings.getOrDefault("down_number", false)) return false;
        if (currentFieldCards.size() != 1 || selected.size() != 1) return false;
        Card fieldCard = currentFieldCards.get(0);
        Card myCard = selected.get(0);
        if (fieldCard == null || myCard == null) return false;
        if (fieldCard.isJoker() || myCard.isJoker()) return false;
        if (!Objects.equals(myCard.getSuit(), fieldCard.getSuit())) return false;

        int targetNum;
        if (isEffectiveRevolution()) {
            targetNum = (fieldCard.getNumber() == 13) ? 1 : (fieldCard.getNumber() == 1 ? 2 : fieldCard.getNumber() + 1);
        } else {
            targetNum = (fieldCard.getNumber() == 2) ? 1 : (fieldCard.getNumber() == 1 ? 13 : fieldCard.getNumber() - 1);
        }
        return myCard.getNumber() == targetNum;
    }

    boolean isValidPlay(List<Card> selected) {
        if (selected == null || selected.isEmpty()) return false;

        if (checkIsDownNumber(selected)) {
            return true;
        }

        boolean isStairs = checkIsStairs(selected);
        boolean isSame = checkIsSameNumber(selected);

        if (!currentFieldCards.isEmpty() && !currentFieldIsStairs && isSame) {
            isStairs = false;
        }

        if (waitingForFourStop && fourStopMode > 0) {
            int reqFour = (fourStopMode == 1) ? 2 : eightCardCount * 2;
            int baseNum = -1;
            for (Card c : selected) {
                if (!c.isJoker()) {
                    baseNum = c.getNumber();
                    break;
                }
            }
            boolean isFours = isSame && (baseNum == 4 || selected.stream().allMatch(Card::isJoker));
            return isFours && selected.size() >= reqFour;
        }

        if (!isStairs && !isSame) return false;

        boolean isJokerSingle = selected.size() == 1 && selected.get(0).isJoker();

        if (ruleSettings.getOrDefault("space_three", true) && jokerCount > 0) {
            if (currentFieldCards.size() == 1 && currentFieldCards.get(0).isJoker()) {
                if (selected.size() == 1 && "♠".equals(selected.get(0).getSuit()) && selected.get(0).getNumber() == 3) {
                    return true;
                }
            }
        }

        if (currentFieldCards.isEmpty()) {
            return true;
        }

        if (selected.size() != currentFieldCards.size()) {
            return false;
        }

        if (currentFieldIsStairs != isStairs) {
            return false;
        }

        int selectedStr;
        if (isStairs) {
            selectedStr = getStairsStrength(selected, isEffectiveRevolution());
        } else if (isSame) {
            Card nonJoker = selected.stream().filter(c -> !c.isJoker()).findFirst().orElse(null);
            selectedStr = (nonJoker != null) ? nonJoker.getStrength(isEffectiveRevolution()) : 14;
        } else {
            selectedStr = selected.stream().mapToInt(c -> c.getStrength(isEffectiveRevolution())).max().orElse(0);
        }

        int fieldStr;
        if (currentFieldIsStairs) {
            fieldStr = getStairsStrength(currentFieldCards, isEffectiveRevolution());
        } else if (checkIsSameNumber(currentFieldCards)) {
            Card nonJoker = currentFieldCards.stream().filter(c -> !c.isJoker()).findFirst().orElse(null);
            fieldStr = (nonJoker != null) ? nonJoker.getStrength(isEffectiveRevolution()) : 14;
        } else {
            fieldStr = currentFieldCards.stream().mapToInt(c -> c.getStrength(isEffectiveRevolution())).max().orElse(0);
        }

        if (!isJokerSingle) {
            if (isNumberLocked && selectedStr != fieldStr + 1) {
                return false;
            }

            if (!lockedSuits.isEmpty()) {
                Set<String> selSuits = getCardSuits(selected);
                int jokersInSelected = (int) selected.stream().filter(Card::isJoker).count();
                if (!lockedSuits.containsAll(selSuits) || (lockedSuits.size() - selSuits.size() > jokersInSelected)) {
                    return false;
                }
            }
        }

        return selectedStr > fieldStr;
    }
}
