package com.desertkun.brainout.bot;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.BrainOutServer;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.client.PlayerClient;
import com.desertkun.brainout.mode.freeplay.BotThreat;

/**
 * Skill of the bots filling matches (deathmatch, capture the flag...), picked every time a bot
 * spawns. MATCH_BOT_DIFFICULTY sets it: "adaptive" (the default) follows the level and the current
 * match of the real players, so every bot gets its own skill around theirs; easy, normal, hard,
 * nightmare or mix force it.
 */
public class MatchBotSkill
{
    private static String setting = System.getenv("MATCH_BOT_DIFFICULTY");

    public static String getSetting()
    {
        return setting == null || setting.trim().isEmpty() ? "adaptive" : setting.trim().toLowerCase();
    }

    public static void setSetting(String value)
    {
        setting = value;
    }

    public static BotDifficulty pick()
    {
        String current = getSetting();

        if (!current.equals("adaptive"))
            return BotDifficulty.parse(current);

        return BotThreat.difficultyFor(tier());
    }

    // 0 to 3 like BotThreat, from the best real player on the server
    private static int tier()
    {
        int best = -1;

        // a separate iterator: callers may already be iterating over the clients
        for (ObjectMap.Entry<Integer, Client> entry : new ObjectMap.Entries<>(BrainOutServer.Controller.getClients()))
        {
            if (!(entry.value instanceof PlayerClient))
                continue;

            Client player = entry.value;
            int level = player.getLevel(Constants.User.LEVEL, 1);

            int tier = level < 8 ? 0 : level < 20 ? 1 : level < 40 ? 2 : 3;

            // dominating the current match: tougher bots
            float ratio = player.getKills() / (float) Math.max(1, player.getDeaths());
            if (player.getKills() >= 5 && ratio >= 3)
            {
                tier += 2;
            }
            else if (player.getKills() >= 3 && ratio >= 1.5f)
            {
                tier += 1;
            }

            best = Math.max(best, tier);
        }

        int tier = best >= 0 ? best : 1;

        // every bot its own skill: some weaker, some stronger than the target
        int roll = MathUtils.random(9);
        if (roll <= 2)
        {
            tier--;
        }
        else if (roll >= 8)
        {
            tier++;
        }

        return MathUtils.clamp(tier, 0, BotThreat.MAX_TIER);
    }
}
