package com.desertkun.brainout.content.components;

import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;
import com.desertkun.brainout.client.Client;
import com.desertkun.brainout.content.components.base.ContentComponent;
import com.desertkun.brainout.data.active.PlayerData;
import com.desertkun.brainout.data.components.base.Component;
import com.desertkun.brainout.data.components.base.ComponentObject;

public abstract class ServerItemActivatorComponent extends ContentComponent
{
    private float time;

    public ServerItemActivatorComponent()
    {
        this.time = 1.0f;
    }

    @Override
    public Component getComponent(ComponentObject componentObject)
    {
        return null;
    }

    // client is a PlayerClient for real players, a BotClient for freeplay bots
    public abstract boolean activate(Client client, PlayerData playerData, int quality);

    @Override
    public void read(Json json, JsonValue jsonData)
    {
        time = jsonData.getFloat("time", 1.0f);
    }

    @Override
    public void write(Json json)
    {

    }

    public float getTime()
    {
        return time;
    }
}
