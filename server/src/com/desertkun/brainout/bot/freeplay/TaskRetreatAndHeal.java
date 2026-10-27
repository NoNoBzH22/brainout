package com.desertkun.brainout.bot.freeplay;

import com.badlogic.gdx.math.Vector2;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.bot.Task;
import com.desertkun.brainout.bot.TaskFollowAndShootTarget;
import com.desertkun.brainout.bot.TaskStack;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.active.ActiveData;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.HealthComponentData;
import com.desertkun.brainout.utils.RandomValue;

/**
 * Breaks off a fight that is going badly: run away from the enemy, out of its sight,
 * patch up, then get back to whatever the bot was doing. Cornered, it fights back.
 */
public class TaskRetreatAndHeal extends Task
{
    private final ActiveData enemy;
    private ActiveData shelter;
    private float timer, timeLeft, outOfSight;
    private boolean healing;

    public TaskRetreatAndHeal(TaskStack stack, ActiveData enemy)
    {
        super(stack);

        this.enemy = enemy;
        this.timeLeft = 25;
    }

    @Override
    protected void update(float dt)
    {
        timer -= dt;
        if (timer > 0)
            return;

        timer = 0.1f;
        timeLeft -= 0.1f;

        PlayerData playerData = getPlayerData();
        if (playerData == null || timeLeft < 0)
        {
            done();
            return;
        }

        setAim(false);
        openFire(false);

        boolean seen = enemy != null && enemy.isAlive() &&
            getController().checkVisibility(enemy, getController().getDifficulty().viewDistance, null);

        if (seen && enemy != null &&
            Vector2.dst2(enemy.getX(), enemy.getY(), playerData.getX(), playerData.getY()) < 10 * 10)
        {
            // cornered: fight back
            getController().stopFollowing();
            popMeAndPushTask(new TaskFollowAndShootTarget(getStack(), enemy,
                new RandomValue(0.3f, 0.8f), new RandomValue(0.3f, 0.6f)));
            return;
        }

        outOfSight = seen ? 0 : outOfSight + 0.1f;

        if (!healing)
        {
            if (outOfSight > 1.5f || shelter == null && !pickShelter(playerData))
            {
                healing = true;
                getController().stopFollowing();
                return;
            }

            if (!getController().isFollowing(shelter))
            {
                getController().follow(shelter, () -> healing = true, () -> healing = true, blocks -> {});
            }

            return;
        }

        // patch up until healthy enough or out of supplies
        if (SurvivalNeeds.Ensure(this))
            return;

        HealthComponentData health = playerData.getComponentWithSubclass(HealthComponentData.class);
        if (health == null || health.getHealth() >= health.getInitHealth() * getController().getDifficulty().healThreshold
            || !SurvivalNeeds.HasHealing(playerData))
        {
            done();
        }
    }

    private void done()
    {
        getController().stopFollowing();
        pop();
    }

    // somewhere on the other side from the enemy, not too close, not too far
    private boolean pickShelter(PlayerData playerData)
    {
        Map map = getMap();
        if (map == null)
            return false;

        float awayX = enemy != null ? Math.signum(playerData.getX() - enemy.getX()) : 0;
        float bestScore = 0;

        for (int tag : new int[]{Constants.ActiveTags.ITEM, Constants.ActiveTags.MARKER, Constants.ActiveTags.SPAWNABLE})
        {
            for (ActiveData candidate : map.getActivesForTag(tag, false))
            {
                float fromMe = Vector2.dst(candidate.getX(), candidate.getY(), playerData.getX(), playerData.getY());
                if (fromMe < 12 || fromMe > 64)
                    continue;

                if (awayX != 0 && Math.signum(candidate.getX() - playerData.getX()) != awayX)
                    continue;

                float score = enemy != null ?
                    Vector2.dst(candidate.getX(), candidate.getY(), enemy.getX(), enemy.getY()) : fromMe;

                if (score > bestScore)
                {
                    bestScore = score;
                    shelter = candidate;
                }
            }
        }

        return shelter != null;
    }
}
