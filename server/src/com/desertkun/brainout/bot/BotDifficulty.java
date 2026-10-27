package com.desertkun.brainout.bot;

import com.badlogic.gdx.math.MathUtils;

public enum BotDifficulty
{
    //          aim spread  turn speed  reaction  view   damage  heal at
    easy       (2.5f,       0.25f,      1.2f,     32f,   0.5f,   0.35f),
    normal     (1.0f,       0.6f,       0.3f,     48f,   0.67f,  0.5f),
    hard       (0.6f,       0.8f,       0.2f,     56f,   1.0f,   0.6f),
    nightmare  (0.3f,       0.95f,      0.1f,     64f,   1.25f,  0.7f);

    // multiplier of the random angle added to every shot
    public final float aimSpread;
    // fraction of the remaining angle turned towards the target per update
    public final float turnSpeed;
    // seconds between noticing an enemy and the first shot
    public final float reactionTime;
    // how far an enemy can be noticed
    public final float viewDistance;
    // multiplier of the damage dealt to real players
    public final float damageToPlayers;
    // health fraction under which the bot heals itself
    public final float healThreshold;

    BotDifficulty(float aimSpread, float turnSpeed, float reactionTime,
                  float viewDistance, float damageToPlayers, float healThreshold)
    {
        this.aimSpread = aimSpread;
        this.turnSpeed = turnSpeed;
        this.reactionTime = reactionTime;
        this.viewDistance = viewDistance;
        this.damageToPlayers = damageToPlayers;
        this.healThreshold = healThreshold;
    }

    /**
     * Parses "easy", "normal", "hard", "nightmare" or "mix" (random for each call).
     * Unknown values fall back to normal.
     */
    public static BotDifficulty parse(String value)
    {
        if (value == null)
            return normal;

        value = value.trim().toLowerCase();

        if (value.equals("mix") || value.equals("mixed") || value.equals("random"))
        {
            BotDifficulty[] all = values();
            return all[MathUtils.random(all.length - 1)];
        }

        for (BotDifficulty difficulty : values())
        {
            if (difficulty.name().equals(value))
                return difficulty;
        }

        return normal;
    }
}
