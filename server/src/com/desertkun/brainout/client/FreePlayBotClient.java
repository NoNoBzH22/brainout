package com.desertkun.brainout.client;

import com.badlogic.gdx.math.MathUtils;
import com.desertkun.brainout.BrainOutServer;
import com.desertkun.brainout.bot.BotDifficulty;
import com.desertkun.brainout.content.active.FreePlayPlayer;
import com.desertkun.brainout.content.active.Player;
import com.desertkun.brainout.content.shop.PlayerSkinSlotItem;
import com.desertkun.brainout.content.shop.PlayerSlotItem;
import com.desertkun.brainout.content.shop.Slot;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.BotControllerComponentData;
import com.desertkun.brainout.server.ServerController;

/**
 * A bot that roams the open world like a player: it spawns as a regular freeplay player
 * (hunger, thirst, cold, radiation, bleeding), loots, heals and fights.
 */
public class FreePlayBotClient extends BotClient
{
    private BotDifficulty difficulty = BotDifficulty.normal;
    // difficulty and gear picked at every spawn from the players around, see BotThreat
    private boolean adaptive;

    public FreePlayBotClient(int id, ServerController serverController)
    {
        super(id, serverController);
    }

    public BotDifficulty getDifficulty()
    {
        return difficulty;
    }

    public void setDifficulty(BotDifficulty difficulty)
    {
        this.difficulty = difficulty;

        PlayerData playerData = getPlayerData();
        if (playerData != null)
        {
            BotControllerComponentData bot = playerData.getComponentWithSubclass(BotControllerComponentData.class);
            if (bot != null)
            {
                bot.setDifficulty(difficulty);
            }
        }
    }

    public boolean isAdaptive()
    {
        return adaptive;
    }

    public void setAdaptive(boolean adaptive)
    {
        this.adaptive = adaptive;
    }

    @Override
    protected float spawnCheckTimer()
    {
        return MathUtils.random(30.f, 60.f);
    }

    @Override
    protected Player getSpawnPlayer()
    {
        return BrainOutServer.ContentMgr.get("player-freeplay", FreePlayPlayer.class);
    }

    @Override
    protected String getBotContent()
    {
        return "sl-pl-freeplay";
    }

    @Override
    protected boolean isMapOkayToSpawn(Map map)
    {
        return !map.isSafeMap();
    }

    @Override
    protected void beforeInit(PlayerData playerData)
    {
        super.beforeInit(playerData);

        BotControllerComponentData bot = playerData.getComponentWithSubclass(BotControllerComponentData.class);
        if (bot != null)
        {
            bot.setDifficulty(difficulty);
        }
    }

    @Override
    protected void setupShopCart()
    {
        getShopCart().clear();
        getShopCart().addDefaultItems();

        Slot playerSlot = BrainOutServer.ContentMgr.get("slot-player", Slot.class);
        PlayerSlotItem botSlotItem = BrainOutServer.ContentMgr.get(getBotContent(), PlayerSlotItem.class);

        if (playerSlot != null && botSlotItem != null)
        {
            getShopCart().selectItem(playerSlot, botSlotItem.getStaticSelection());
        }

        Slot playerSkinSlot = BrainOutServer.ContentMgr.get("slot-player-skin", Slot.class);
        PlayerSkinSlotItem playerSkin = getRandomPlayerSkin();
        if (playerSkin != null)
        {
            getShopCart().selectItem(playerSkinSlot, playerSkin.getStaticSelection());
        }
    }
}
