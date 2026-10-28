package com.desertkun.brainout.bot;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.bot.freeplay.TakeItem;
import com.desertkun.brainout.client.BotClient;
import com.desertkun.brainout.components.PlayerOwnerComponent;
import com.desertkun.brainout.content.consumable.impl.InstrumentConsumableItem;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.active.ActiveData;
import com.desertkun.brainout.data.active.ItemData;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.ServerChipItemComponentData;
import com.desertkun.brainout.data.components.ServerChipReceiverComponentData;
import com.desertkun.brainout.data.consumable.ConsumableRecord;
import com.desertkun.brainout.data.instrument.ChipData;
import com.desertkun.brainout.utils.RandomValue;
import com.esotericsoftware.minlog.Log;

/**
 * Capture the flag: chips are dropped around the map, a team scores by bringing them into its
 * receiver, and chips can be stolen from the other team's receiver.
 *
 * Attackers fetch a chip that is not already in their receiver (stealing included) and bring it
 * home; defenders (the "protect" role) stay around their receiver. Everybody fights enemies in
 * sight, except a chip carrier close to home, who runs to score.
 */
public class TaskCaptureTheFlag extends Task
{
    private static final boolean DEBUG = System.getenv("BOT_DEBUG") != null;

    private final BotClient client;
    private float timer;
    private ActiveData moveTarget;

    public TaskCaptureTheFlag(TaskStack stack, BotClient client)
    {
        super(stack);

        this.client = client;
    }

    @Override
    protected void update(float dt)
    {
        timer -= dt;
        if (timer > 0)
            return;

        timer = 0.25f;

        PlayerData playerData = getPlayerData();
        Map map = getMap();
        if (playerData == null || map == null)
            return;

        ConsumableRecord chip = carriedChip(playerData);
        ActiveData home = ownReceiver(map, playerData);

        float homeDistance = home != null ?
            Vector2.dst(home.getX(), home.getY(), playerData.getX(), playerData.getY()) : Float.MAX_VALUE;

        ActiveData enemy = checkForEnemies();
        if (enemy != null && !(chip != null && homeDistance < 24))
        {
            moveTarget = null;
            getController().stopFollowing();
            pushTask(new TaskFollowAndShootTarget(getStack(), enemy,
                new RandomValue(0.2f, 0.7f), new RandomValue(0.3f, 0.8f)));
            return;
        }

        if (chip != null)
        {
            if (home == null)
                return;

            if (homeDistance < 2.5f)
            {
                // dropping the chip inside the receiver scores it
                getController().stopFollowing();
                moveTarget = null;
                getController().dropConsumable(chip.getId(), playerData.getAngle(), 1);
                if (DEBUG) Log.info("[ctf] " + client.getName() + " brings a chip home");
                return;
            }

            moveTo(home);
            return;
        }

        if (client.getRole() == BotClient.Role.protect && home != null)
        {
            if (homeDistance > 6)
            {
                moveTo(home);
            }
            else
            {
                getController().stopFollowing();
                moveTarget = null;
            }
            return;
        }

        ItemData target = closestChip(map, playerData, home);
        if (target != null)
        {
            ConsumableRecord record = chipRecord(target);
            if (record != null)
            {
                moveTarget = null;
                getController().stopFollowing();
                pushTask(new TakeItem(getStack(), target, record, 1, (stack, noticed) ->
                {
                    pushTask(new TaskFollowAndShootTarget(stack, noticed,
                        new RandomValue(0.2f, 0.7f), new RandomValue(0.3f, 0.8f)));
                    return true;
                }, taken -> { if (DEBUG) Log.info("[ctf] " + client.getName() + " took a chip"); }));
                return;
            }
        }

        // nothing to fetch: look for a fight
        moveTarget = null;
        pushTask(new TaskHunter(getStack(), client));
    }

    private void moveTo(ActiveData target)
    {
        if (moveTarget == target && getController().isFollowing(target))
            return;

        moveTarget = target;
        getController().follow(target, () -> moveTarget = null, () -> moveTarget = null, blocks -> {});
    }

    private static boolean isChip(ConsumableRecord record)
    {
        return record.getItem() instanceof InstrumentConsumableItem &&
            ((InstrumentConsumableItem) record.getItem()).getInstrumentData() instanceof ChipData;
    }

    private static ConsumableRecord carriedChip(PlayerData playerData)
    {
        PlayerOwnerComponent poc = playerData.getComponent(PlayerOwnerComponent.class);
        if (poc == null)
            return null;

        for (ObjectMap.Entry<Integer, ConsumableRecord> entry : poc.getConsumableContainer().getData())
        {
            if (isChip(entry.value))
                return entry.value;
        }

        return null;
    }

    private static ConsumableRecord chipRecord(ItemData item)
    {
        for (ObjectMap.Entry<Integer, ConsumableRecord> entry : item.getRecords().getData())
        {
            if (isChip(entry.value))
                return entry.value;
        }

        return null;
    }

    private static ActiveData ownReceiver(Map map, PlayerData playerData)
    {
        for (ActiveData receiver : map.getActivesForTag(Constants.ActiveTags.CHIP_RECEIVER, false))
        {
            if (receiver.getTeam() == playerData.getTeam())
                return receiver;
        }

        return null;
    }

    // the closest chip on the ground or in the enemy receiver; chips already home are left alone
    private ItemData closestChip(Map map, PlayerData playerData, ActiveData home)
    {
        ItemData best = null;
        float bestDistance = Float.MAX_VALUE;

        for (ActiveData active : map.getActivesForTag(Constants.ActiveTags.CHIP, false))
        {
            if (!(active instanceof ItemData))
                continue;

            ItemData item = ((ItemData) active);

            if (getController().getUnreachableItems().contains(item.getId()))
                continue;

            // just dropped at home, not counted yet
            if (home != null && Vector2.dst2(item.getX(), item.getY(), home.getX(), home.getY()) < 5 * 5)
                continue;

            ServerChipItemComponentData chipItem = item.getComponent(ServerChipItemComponentData.class);
            if (chipItem != null && chipItem.isDelivered())
            {
                ServerChipReceiverComponentData receiver = chipItem.getDelivered();
                if (receiver != null && receiver.getTeam() == playerData.getTeam())
                    continue;
            }

            float distance = Vector2.dst2(item.getX(), item.getY(), playerData.getX(), playerData.getY());
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = item;
            }
        }

        return best;
    }
}
