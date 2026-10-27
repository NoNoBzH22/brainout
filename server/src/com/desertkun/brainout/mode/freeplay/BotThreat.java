package com.desertkun.brainout.mode.freeplay;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.IntIntMap;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.BrainOutServer;
import com.desertkun.brainout.bot.BotDifficulty;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.client.PlayerClient;
import com.desertkun.brainout.components.PlayerOwnerComponent;
import com.desertkun.brainout.content.consumable.impl.ArmorConsumableItem;
import com.desertkun.brainout.content.consumable.impl.InstrumentConsumableItem;
import com.desertkun.brainout.content.instrument.Weapon;
import com.desertkun.brainout.content.shop.Slot;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.MapDimensionsGraph;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.consumable.ConsumableRecord;
import com.desertkun.brainout.data.instrument.WeaponData;

/**
 * Rates how dangerous the real players are, so adaptive bots match them: a newcomer with a
 * pistol meets poorly equipped, clumsy bots, a well armed player on a killing spree meets
 * well equipped, sharp ones.
 *
 * Tiers: 0 melee only, 1 pistol, 2 regular primary weapon, 3 heavy weapon or armored.
 */
public class BotThreat
{
    public static final int MAX_TIER = 3;

    // bots killed by each player since their last death
    private static final IntIntMap killStreaks = new IntIntMap();

    public static void botKilledBy(Client killer)
    {
        if (killer instanceof PlayerClient)
        {
            killStreaks.getAndIncrement(killer.getId(), 0, 1);
        }
    }

    public static void playerDied(Client player)
    {
        killStreaks.remove(player.getId(), 0);
    }

    public static int gearTier(PlayerData playerData)
    {
        PlayerOwnerComponent poc = playerData.getComponent(PlayerOwnerComponent.class);
        if (poc == null)
            return 0;

        int tier = 0;
        boolean armored = false;

        for (ObjectMap.Entry<Integer, ConsumableRecord> entry : poc.getConsumableContainer().getData())
        {
            if (entry.value.getItem() instanceof ArmorConsumableItem)
            {
                armored = true;
                continue;
            }

            if (!(entry.value.getItem() instanceof InstrumentConsumableItem))
                continue;

            if (!(((InstrumentConsumableItem) entry.value.getItem()).getInstrumentData() instanceof WeaponData))
                continue;

            Weapon weapon = ((WeaponData) ((InstrumentConsumableItem) entry.value.getItem()).getInstrumentData()).getWeapon();
            Slot slot = weapon.getSlot();

            if (slot == null)
                continue;

            if (slot.getID().equals("slot-secondary"))
            {
                tier = Math.max(tier, 1);
            }
            else if (slot.getID().equals("slot-primary"))
            {
                Weapon.WeaponProperties properties = weapon.getPrimaryProperties();
                boolean heavy = properties.getDamage() >= 100 || properties.getClipSize() >= 60 || properties.getDamage() >= 60;
                tier = Math.max(tier, heavy ? 3 : 2);
            }
        }

        if (armored)
        {
            tier = Math.min(MAX_TIER, tier + 1);
        }

        return tier;
    }

    public static int playerTier(PlayerClient player)
    {
        PlayerData playerData = player.getPlayerData();
        if (playerData == null)
            return 0;

        int tier = gearTier(playerData);
        int streak = killStreaks.get(player.getId(), 0);

        // players who keep winning get tougher opponents
        if (streak >= 6)
        {
            tier += 2;
        }
        else if (streak >= 3)
        {
            tier += 1;
        }

        return Math.min(MAX_TIER, tier);
    }

    /** The tier of a bot about to spawn in the given dimension. */
    public static int tierFor(String dimension)
    {
        Map spawnMap = Map.Get(dimension);

        int nearby = -1;
        int total = 0, count = 0;

        for (ObjectMap.Entry<Integer, Client> entry : BrainOutServer.Controller.getClients())
        {
            if (!(entry.value instanceof PlayerClient) || !entry.value.isAlive())
                continue;

            PlayerClient player = ((PlayerClient) entry.value);
            PlayerData playerData = player.getPlayerData();
            if (playerData == null)
                continue;

            int tier = playerTier(player);
            total += tier;
            count++;

            Map playerMap = playerData.getMap();
            if (spawnMap != null && playerMap != null &&
                (playerMap == spawnMap || MapDimensionsGraph.IsNeighbor(playerMap, spawnMap)))
            {
                nearby = Math.max(nearby, tier);
            }
        }

        int tier;

        if (nearby >= 0)
        {
            tier = nearby;
        }
        else if (count > 0)
        {
            tier = Math.round((float) total / count);
        }
        else
        {
            tier = 1;
        }

        // some variety, mostly around the target
        int roll = MathUtils.random(9);
        if (roll == 0)
        {
            tier--;
        }
        else if (roll == 9)
        {
            tier++;
        }

        return MathUtils.clamp(tier, 0, MAX_TIER);
    }

    public static BotDifficulty difficultyFor(int tier)
    {
        switch (tier)
        {
            case 0:
                return BotDifficulty.easy;
            case 1:
                return MathUtils.randomBoolean(0.7f) ? BotDifficulty.easy : BotDifficulty.normal;
            case 2:
                return MathUtils.randomBoolean(0.7f) ? BotDifficulty.normal : BotDifficulty.hard;
            default:
                return MathUtils.randomBoolean(0.6f) ? BotDifficulty.hard : BotDifficulty.nightmare;
        }
    }
}
