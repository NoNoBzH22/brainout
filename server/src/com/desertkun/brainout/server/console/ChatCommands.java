package com.desertkun.brainout.server.console;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.BrainOutServer;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.bot.BotDifficulty;
import com.desertkun.brainout.bot.BotTuning;
import com.desertkun.brainout.bot.MatchBotSkill;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.client.FreePlayBotClient;
import com.desertkun.brainout.client.PlayerClient;
import com.desertkun.brainout.content.Content;
import com.desertkun.brainout.content.consumable.ConsumableContent;
import com.desertkun.brainout.content.instrument.Instrument;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.HealthComponentData;
import com.desertkun.brainout.data.components.ServerFreeplayPlayerComponentData;
import com.desertkun.brainout.mode.GameMode;
import com.desertkun.brainout.mode.ServerFreeRealization;
import com.desertkun.brainout.online.PlayerRights;

import java.util.Locale;

/**
 * Commands typed in the chat on private servers, starting with "/": /squad and /aide for everyone,
 * the rest for admins (the ADMINS setting of the server).
 */
public class ChatCommands
{
    private static final String HELP_PLAYER =
        "/squad NOM : rejoindre une escouade, /squad : la quitter";

    private static final String[] HELP_ADMIN = {
        "/give OBJET [nombre] : se donner un objet (ex: /give ak74, /give bandage 5)",
        "/soin : soin complet   /god : invincible on/off",
        "/jour /nuit /heure 0-23   /pluie /soleil",
        "/tp JOUEUR : aller vers lui   /tpici JOUEUR : le faire venir   /kick JOUEUR",
        "/joueurs : qui est en ligne   /bots [nombre]   /xp NOMBRE [JOUEUR] : donner de l'XP",
        "/difficulte adaptive|easy|normal|hard|nightmare|mix",
        "/botdegats 0.1-5 (1 = normal)   /botprecision 0.1-5 (1 = normal)",
    };

    /** @return true if the text was a command (it is not sent to the chat then) */
    public static boolean handle(PlayerClient player, String text)
    {
        if (!text.startsWith("/"))
            return false;

        String[] args = text.substring(1).trim().split("\\s+");
        String command = args[0].toLowerCase(Locale.ROOT);

        switch (command)
        {
            case "aide":
            case "help":
            {
                player.sendSystemMessage(HELP_PLAYER);
                if (isAdmin(player))
                {
                    for (String line : HELP_ADMIN)
                    {
                        player.sendSystemMessage(line);
                    }
                }
                return true;
            }
        }

        if (!isAdmin(player))
        {
            player.sendSystemMessage("Commande inconnue. Tape /aide.");
            return true;
        }

        ServerFreeRealization free = getFree();

        try
        {
            switch (command)
            {
                case "give":
                    give(player, args);
                    return true;
                case "soin":
                case "heal":
                    heal(player);
                    return true;
                case "god":
                    god(player);
                    return true;
                case "jour":
                case "day":
                    setHour(player, free, 12);
                    return true;
                case "nuit":
                case "night":
                    setHour(player, free, 23);
                    return true;
                case "heure":
                case "time":
                    setHour(player, free, Integer.parseInt(arg(args, 1, "12")));
                    return true;
                case "pluie":
                case "rain":
                    setRain(player, free, true);
                    return true;
                case "soleil":
                case "sun":
                    setRain(player, free, false);
                    return true;
                case "tp":
                    teleport(player, find(player, arg(args, 1, "")), player);
                    return true;
                case "tpici":
                    teleport(player, player, find(player, arg(args, 1, "")));
                    return true;
                case "kick":
                {
                    PlayerClient target = find(player, arg(args, 1, ""));
                    if (target != null && target != player)
                    {
                        target.kick("Expulse par un admin");
                        player.sendSystemMessage(target.getName() + " a ete expulse.");
                    }
                    return true;
                }
                case "xp":
                {
                    int amount = Integer.parseInt(arg(args, 1, "0"));
                    PlayerClient target = args.length > 2 ? find(player, args[2]) : player;
                    if (target != null && amount > 0)
                    {
                        target.addScore(amount, true);
                        player.sendSystemMessage(target.getName() + " +" + amount + " XP (niveau " +
                            target.getLevel(Constants.User.LEVEL, 1) + ")");
                    }
                    return true;
                }
                case "joueurs":
                case "players":
                    players(player, free);
                    return true;
                case "bots":
                    if (free == null)
                        break;
                    if (args.length > 1)
                    {
                        free.setFreeplayBots(Integer.parseInt(args[1]));
                    }
                    player.sendSystemMessage("Bots : " + free.getFreeplayBots() + " (" + free.getFreeplayBotsDifficulty() + ")");
                    return true;
                case "difficulte":
                case "difficulty":
                {
                    String difficulty = arg(args, 1, "adaptive").toLowerCase(Locale.ROOT);
                    if (!difficulty.equals("adaptive") && !difficulty.equals("mix") && !isDifficulty(difficulty))
                    {
                        player.sendSystemMessage("Difficultes : adaptive, easy, normal, hard, nightmare, mix");
                        return true;
                    }
                    if (free != null)
                    {
                        free.setFreeplayBotsDifficulty(difficulty);
                    }
                    else
                    {
                        // matches: applies from the next respawn of each bot
                        MatchBotSkill.setSetting(difficulty);
                    }
                    player.sendSystemMessage("Difficulte des bots : " + difficulty);
                    return true;
                }
                case "botdegats":
                    if (args.length > 1)
                    {
                        BotTuning.setDamage(Float.parseFloat(args[1].replace(',', '.')));
                    }
                    player.sendSystemMessage("Degats des bots x" + BotTuning.getDamage());
                    return true;
                case "botprecision":
                    if (args.length > 1)
                    {
                        BotTuning.setAccuracy(Float.parseFloat(args[1].replace(',', '.')));
                    }
                    player.sendSystemMessage("Precision des bots x" + BotTuning.getAccuracy());
                    return true;
            }
        }
        catch (NumberFormatException e)
        {
            player.sendSystemMessage("Nombre invalide.");
            return true;
        }

        player.sendSystemMessage("Commande inconnue. Tape /aide.");
        return true;
    }

    private static boolean isAdmin(PlayerClient player)
    {
        return player.getRights() == PlayerRights.admin || player.getRights() == PlayerRights.owner;
    }

    private static boolean isDifficulty(String name)
    {
        for (BotDifficulty difficulty : BotDifficulty.values())
        {
            if (difficulty.name().equals(name))
                return true;
        }

        return false;
    }

    private static String arg(String[] args, int index, String def)
    {
        return args.length > index ? args[index] : def;
    }

    private static ServerFreeRealization getFree()
    {
        GameMode mode = BrainOutServer.Controller.getGameMode();
        if (mode != null && mode.getRealization() instanceof ServerFreeRealization)
            return ((ServerFreeRealization) mode.getRealization());

        return null;
    }

    // "/give ak74 2": finds the item whose id contains every word, preferring exact matches
    private static void give(PlayerClient player, String[] args)
    {
        if (args.length < 2)
        {
            player.sendSystemMessage("Utilisation : /give OBJET [nombre]");
            return;
        }

        int amount = 1;
        String search = args[1].toLowerCase(Locale.ROOT);

        if (args.length > 2)
        {
            amount = Math.max(1, Math.min(1000, Integer.parseInt(args[2])));
        }

        Array<Content> found = BrainOutServer.ContentMgr.queryContent(Content.class, content ->
            (content instanceof Instrument || content instanceof ConsumableContent) &&
                content.getID().toLowerCase(Locale.ROOT).contains(search));

        Content best = null;
        for (Content content : found)
        {
            String id = content.getID().toLowerCase(Locale.ROOT);
            if (id.equals(search) || id.equals("weapon-" + search) || id.equals("consumable-item-" + search))
            {
                best = content;
                break;
            }
        }

        if (best == null && found.size == 1)
        {
            best = found.first();
        }

        if (best == null)
        {
            if (found.size == 0)
            {
                player.sendSystemMessage("Aucun objet ne correspond a \"" + search + "\".");
                return;
            }

            StringBuilder list = new StringBuilder("Plusieurs objets : ");
            for (int i = 0; i < found.size && i < 12; i++)
            {
                list.append(i > 0 ? ", " : "").append(found.get(i).getID());
            }
            if (found.size > 12)
            {
                list.append("... (").append(found.size).append(")");
            }
            player.sendSystemMessage(list.toString());
            return;
        }

        String result = BrainOutServer.Controller.getConsole().executeParams(player,
            new String[]{"give", best.getID(), String.valueOf(amount)}, player);

        player.sendSystemMessage(best.getID() + " x" + amount + " : " + result);
    }

    private static void heal(PlayerClient player)
    {
        PlayerData playerData = player.getPlayerData();
        ServerFreeplayPlayerComponentData fp = playerData != null ?
            playerData.getComponent(ServerFreeplayPlayerComponentData.class) : null;

        if (fp == null)
        {
            player.sendSystemMessage("Tu dois etre en jeu.");
            return;
        }

        fp.fullRecovery();
        player.sendSystemMessage("Soigne.");
    }

    private static void god(PlayerClient player)
    {
        PlayerData playerData = player.getPlayerData();
        HealthComponentData health = playerData != null ? playerData.getComponent(HealthComponentData.class) : null;

        if (health == null)
        {
            player.sendSystemMessage("Tu dois etre en jeu.");
            return;
        }

        health.setGod(!health.isGod());
        player.sendSystemMessage("Invincible : " + (health.isGod() ? "oui" : "non"));
    }

    // moves the time of day while keeping the current weather
    private static void setHour(PlayerClient player, ServerFreeRealization free, int hour)
    {
        if (free == null)
            return;

        hour = Math.max(0, Math.min(23, hour));
        int day = Constants.Core.SECONDS_IN_A_DAY;
        long now = System.currentTimeMillis() / 1000L + free.getTimeOfDayOffset();

        // the game's clock: 0 is midnight... night is around 0.8 to 1.0 of the day
        float target = ((hour + 24 - 3) % 24) / 24.0f;
        long startOfDay = now - (now % day);

        free.setTimeOfDayOffset(free.getTimeOfDayOffset() + (int) (startOfDay + (long) (target * day) - now));
        player.sendSystemMessage("Heure changee" + (free.getGameMode().isNight() ? " (nuit)." : "."));
    }

    private static void setRain(PlayerClient player, ServerFreeRealization free, boolean rain)
    {
        if (free == null)
            return;

        int day = Constants.Core.SECONDS_IN_A_DAY;
        int cycle = day * 5;
        long now = System.currentTimeMillis() / 1000L + free.getTimeOfDayOffset();
        long inCycle = now % cycle;
        long timeOfDay = inCycle % day;

        // it rains during the first day of each five days cycle: move to that day or the next, same hour
        long target = (rain ? 0 : day) + timeOfDay;
        free.setTimeOfDayOffset(free.getTimeOfDayOffset() + (int) (target - inCycle));
        player.sendSystemMessage(rain ? "Il pleut." : "Le soleil revient.");
    }

    private static PlayerClient find(PlayerClient asker, String name)
    {
        PlayerClient partial = null;

        for (ObjectMap.Entry<Integer, Client> entry : BrainOutServer.Controller.getClients())
        {
            if (!(entry.value instanceof PlayerClient))
                continue;

            PlayerClient player = ((PlayerClient) entry.value);
            String playerName = player.getName();

            if (playerName.equalsIgnoreCase(name))
                return player;

            if (!name.isEmpty() && playerName.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT)))
            {
                partial = player;
            }
        }

        if (partial == null)
        {
            asker.sendSystemMessage("Joueur introuvable : " + name);
        }

        return partial;
    }

    private static void teleport(PlayerClient asker, PlayerClient who, PlayerClient to)
    {
        if (who == null || to == null)
            return;

        PlayerData target = to.getPlayerData();

        if (target == null || who.getPlayerData() == null)
        {
            asker.sendSystemMessage("Les deux joueurs doivent etre en jeu.");
            return;
        }

        who.moveTo(target.getDimension(), target.getX(), target.getY());
        asker.sendSystemMessage(who.getName() + " -> " + to.getName());
    }

    private static void players(PlayerClient asker, ServerFreeRealization free)
    {
        StringBuilder players = new StringBuilder("En ligne : ");
        int bots = 0, count = 0;

        for (ObjectMap.Entry<Integer, Client> entry : BrainOutServer.Controller.getClients())
        {
            if (entry.value instanceof FreePlayBotClient)
            {
                bots++;
            }
            else if (entry.value instanceof PlayerClient)
            {
                players.append(count++ > 0 ? ", " : "").append(entry.value.getName());
            }
        }

        players.append(" - ").append(bots).append(" bots");
        asker.sendSystemMessage(players.toString());
    }
}
