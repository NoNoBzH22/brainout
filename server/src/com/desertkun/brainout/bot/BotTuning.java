package com.desertkun.brainout.bot;

/**
 * Server wide adjustments on top of the bot difficulties, set with the BOT_DAMAGE and
 * BOT_ACCURACY environment variables or the /botdegats and /botprecision admin commands.
 */
public class BotTuning
{
    // multiplier of the damage bots deal to players
    private static float damage = read("BOT_DAMAGE");
    // 2 means bots spread their shots half as much
    private static float accuracy = read("BOT_ACCURACY");

    private static float read(String name)
    {
        try
        {
            String value = System.getenv(name);
            return value != null ? clamp(Float.parseFloat(value)) : 1.0f;
        }
        catch (NumberFormatException e)
        {
            return 1.0f;
        }
    }

    private static float clamp(float value)
    {
        return Math.max(0.1f, Math.min(5.0f, value));
    }

    public static float getDamage()
    {
        return damage;
    }

    public static void setDamage(float damage)
    {
        BotTuning.damage = clamp(damage);
    }

    public static float getAccuracy()
    {
        return accuracy;
    }

    public static void setAccuracy(float accuracy)
    {
        BotTuning.accuracy = clamp(accuracy);
    }

    /** Multiplier of the random angle added to every shot. */
    public static float getSpread()
    {
        return 1.0f / accuracy;
    }
}
