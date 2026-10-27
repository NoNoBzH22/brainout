package com.desertkun.brainout.bot.freeplay;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.bot.Task;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.bot.TaskFollowAndShootTarget;
import com.desertkun.brainout.components.PlayerOwnerComponent;
import com.desertkun.brainout.content.components.BoosterActivatorComponent;
import com.desertkun.brainout.content.components.ServerBleedingItemActivatorComponent;
import com.desertkun.brainout.content.components.ServerItemActivatorComponent;
import com.desertkun.brainout.content.consumable.ConsumableContent;
import com.desertkun.brainout.data.active.ItemData;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.ActiveProgressComponentData;
import com.desertkun.brainout.data.components.BotControllerComponentData;
import com.desertkun.brainout.data.components.FreeplayPlayerComponentData;
import com.desertkun.brainout.data.components.HealthComponentData;
import com.desertkun.brainout.data.components.PlayerBoostersComponentData;
import com.desertkun.brainout.data.components.ServerFreeplayPlayerComponentData;
import com.desertkun.brainout.data.consumable.ConsumableRecord;
import com.desertkun.brainout.utils.RandomValue;
import com.esotericsoftware.minlog.Log;

/**
 * Makes freeplay bots take care of themselves like players do: stop bleeding, heal,
 * drink, eat, fight radiation, fix bones and warm up, using what they carry or
 * going to look for it.
 */
public class SurvivalNeeds
{
    private enum Need
    {
        bleeding,
        health,
        radiation,
        bones,
        thirst,
        hunger,
        cold
    }

    // how long to stop looking for supplies after a search found nothing
    private static final long SEARCH_COOLDOWN = 60000;
    private static final long SEARCH_RETRY = 20000;
    private static final boolean DEBUG = System.getenv("BOT_DEBUG") != null;

    /**
     * @return true if the bot is busy taking care of itself and the caller should wait
     */
    public static boolean Ensure(Task task)
    {
        PlayerData playerData = task.getPlayerData();
        if (playerData == null || !playerData.isAlive() || playerData.isWounded())
            return false;

        FreeplayPlayerComponentData fp = playerData.getComponent(FreeplayPlayerComponentData.class);
        PlayerOwnerComponent poc = playerData.getComponent(PlayerOwnerComponent.class);
        HealthComponentData health = playerData.getComponentWithSubclass(HealthComponentData.class);
        ActiveProgressComponentData progress = playerData.getComponent(ActiveProgressComponentData.class);

        // a bot without survival stats has nothing to take care of
        if (fp == null || poc == null || health == null || progress == null)
            return false;

        // already bandaging, eating, drinking...
        if (progress.isRunning())
            return true;

        BotControllerComponentData controller = task.getController();
        ServerFreeplayPlayerComponentData serverFp = playerData.getComponent(ServerFreeplayPlayerComponentData.class);

        for (Need need : Need.values())
        {
            if (!isNeeded(need, playerData, fp, serverFp, health, controller))
                continue;

            ConsumableRecord record = findSupply(poc, need, fp, health, playerData);

            if (record != null)
            {
                debug(task, "uses " + record.getItem().getContent().getID() + " for " + need);
                if (!controller.canUseItems())
                    return false;

                controller.activateItem(record);

                if (!progress.isRunning())
                {
                    // could not use it right now (in the air, busy...), try again a bit later
                    controller.blockItemUse(3000);
                    return false;
                }

                return true;
            }

            if (isUrgent(need, fp, health) && controller.canSearchSupplies())
            {
                debug(task, "looks for something against " + need);
                // do not restart the same search every few seconds if the found item cannot be taken
                controller.blockSupplySearch(SEARCH_RETRY);
                lookFor(task, need, fp, health, playerData);
                return true;
            }
        }

        return false;
    }

    /** Whether the bot carries something to stop bleeding or restore health. */
    public static boolean HasHealing(PlayerData playerData)
    {
        PlayerOwnerComponent poc = playerData.getComponent(PlayerOwnerComponent.class);
        if (poc == null)
            return false;

        for (ObjectMap.Entry<Integer, ConsumableRecord> entry : poc.getConsumableContainer().getData())
        {
            if (supplyValue(entry.value, Need.health) > 0 || supplyValue(entry.value, Need.bleeding) > 0)
                return true;
        }

        return false;
    }

    private static void debug(Task task, String message)
    {
        if (DEBUG && Log.INFO)
        {
            Client client = task.getController().getClient();
            Log.info("[bot " + (client != null ? client.getName() : "?") + "] " + message);
        }
    }

    private static boolean isNeeded(Need need, PlayerData playerData, FreeplayPlayerComponentData fp,
                                    ServerFreeplayPlayerComponentData serverFp, HealthComponentData health,
                                    BotControllerComponentData controller)
    {
        switch (need)
        {
            case bleeding:
                return serverFp != null && serverFp.isBleeding();
            case health:
                return health.getHealth() < health.getInitHealth() * controller.getDifficulty().healThreshold;
            case radiation:
                return fp.hasRadioWarning() && !hasRadioProtection(playerData);
            case bones:
                return fp.hasBonesBroken();
            case thirst:
                return fp.getThirst() < fp.getContentComponent().getThirstMax() * 0.5f;
            case hunger:
                return fp.getHunger() < fp.getContentComponent().getHungerMax() * 0.5f;
            case cold:
                return fp.isCold();
        }

        return false;
    }

    // worth dropping everything to go look for a supply
    private static boolean isUrgent(Need need, FreeplayPlayerComponentData fp, HealthComponentData health)
    {
        switch (need)
        {
            case bleeding:
            case bones:
                return true;
            case health:
                return health.getHealth() < health.getInitHealth() * 0.3f;
            case radiation:
                return fp.hasRadioDanger();
            case thirst:
                return fp.getThirst() < fp.getContentComponent().getThirstMax() * 0.25f;
            case hunger:
                return fp.getHunger() < fp.getContentComponent().getHungerMax() * 0.25f;
            default:
                return false;
        }
    }

    private static boolean hasRadioProtection(PlayerData playerData)
    {
        PlayerBoostersComponentData boosters = playerData.getComponent(PlayerBoostersComponentData.class);
        if (boosters == null)
            return false;

        PlayerBoostersComponentData.Booster radx = boosters.getBooster("radx");
        return radx != null && radx.valid();
    }

    private static ConsumableRecord findSupply(PlayerOwnerComponent poc, Need need,
                                               FreeplayPlayerComponentData fp, HealthComponentData health,
                                               PlayerData playerData)
    {
        ConsumableRecord best = null;
        float bestValue = 0;

        for (ObjectMap.Entry<Integer, ConsumableRecord> entry : poc.getConsumableContainer().getData())
        {
            ConsumableRecord record = entry.value;
            float value = supplyValue(record, need);

            if (value > bestValue)
            {
                best = record;
                bestValue = value;
            }
        }

        return best;
    }

    // how well an item covers a need, 0 if it does not
    private static float supplyValue(ConsumableRecord record, Need need)
    {
        if (record == null || record.getItem() == null || !(record.getItem().getContent() instanceof ConsumableContent))
            return 0;

        ConsumableContent content = ((ConsumableContent) record.getItem().getContent());

        if (content.getComponentFrom(ServerItemActivatorComponent.class) == null)
            return 0;

        if (need == Need.bleeding)
        {
            return content.hasComponent(ServerBleedingItemActivatorComponent.class) ? 1 : 0;
        }

        BoosterActivatorComponent booster = content.getComponent(BoosterActivatorComponent.class);
        if (booster == null)
            return 0;

        int quality = record.getQuality();

        switch (need)
        {
            case health:
                return booster.getHealth(quality);
            case radiation:
                return booster.getRadio(quality) + (booster.getBoosters().containsKey("radx") ? 100 : 0);
            case bones:
                return booster.isFixBones() ? 1 : 0;
            case thirst:
                return booster.getThirst(quality);
            case hunger:
                return booster.getHunger(quality);
            case cold:
                return booster.getTemp(quality);
        }

        return 0;
    }

    private static void lookFor(Task task, Need need, FreeplayPlayerComponentData fp,
                                HealthComponentData health, PlayerData playerData)
    {
        BotControllerComponentData controller = task.getController();

        task.pushTask(new FindItem(task.getStack(), new FindItem.ItemPredicate()
        {
            @Override
            public int matches(ItemData itemData, ConsumableRecord record)
            {
                return supplyValue(record, need) > 0 ? 1 : 0;
            }

            @Override
            public void notFound()
            {
                controller.blockSupplySearch(SEARCH_COOLDOWN);
            }
        }, (stack, enemy) ->
        {
            if (Vector2.dst2(enemy.getX(), enemy.getY(), playerData.getX(), playerData.getY()) > 24.0f * 24.0f)
                return false;

            task.pushTask(new TaskFollowAndShootTarget(stack, enemy,
                new RandomValue(0.3f, 0.8f), new RandomValue(0.5f, 1.0f)));
            return true;
        }, record -> {}));
    }
}
