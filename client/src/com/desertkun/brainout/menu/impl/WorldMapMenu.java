package com.desertkun.brainout.menu.impl;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.client.RemoteClient;
import com.desertkun.brainout.client.settings.KeyProperties;
import com.desertkun.brainout.common.msg.client.ChatSendMsg;
import com.desertkun.brainout.menu.ui.ClickOverListener;
import com.desertkun.brainout.common.msg.server.SquadPositionsMsg;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.active.ActiveData;
import com.desertkun.brainout.data.block.BlockData;
import com.desertkun.brainout.data.containers.ChunkData;
import com.desertkun.brainout.menu.Menu;
import com.desertkun.brainout.menu.ui.SquadHud;

/**
 * Full screen map of the open world: the three outdoor areas drawn from their terrain, the
 * building doors, the player and their squad mates (shown at the door of the building they
 * are in). Enemies are never shown.
 */
public class WorldMapMenu extends Menu
{
    private static final String[] AREAS = {"default", "forest", "swamp2"};
    private static final String[] AREA_NAMES = {"Plaine", "Foret", "Marais"};

    private static final Color BACKGROUND = new Color(0, 0, 0, 0.85f);
    private static final Color GROUND = new Color(0.55f, 0.6f, 0.5f, 1);
    private static final Color DOOR = new Color(0.9f, 0.8f, 0.4f, 1);
    private static final Color ME = new Color(1, 1, 1, 1);
    private static final Color MATE = new Color(0.45f, 1.0f, 0.45f, 1);
    private static final Color WOUNDED = new Color(1.0f, 0.65f, 0.2f, 1);

    // terrain pictures, built once: the ground barely changes during a session
    private static final ObjectMap<String, Texture> TERRAIN = new ObjectMap<>();

    private Label squadLabel;
    private TextField squadName;

    @Override
    public Table createUI()
    {
        Table data = new Table();

        MapView view = new MapView();
        view.setBounds(0, 0, BrainOutClient.getWidth(), BrainOutClient.getHeight());
        addActor(view);

        addActor(createSquadPanel());

        return data;
    }

    // join, create or leave a squad: members are allies, spawn together and see each other
    private Table createSquadPanel()
    {
        Table panel = new Table();
        panel.align(Align.topRight);

        squadLabel = new Label("", BrainOutClient.Skin, "title-small");
        panel.add(squadLabel).colspan(3).right().padBottom(6).row();

        squadName = new TextField(currentSquad() != null ? currentSquad() : "", BrainOutClient.Skin, "edit-default");
        squadName.setMessageText("nom de l'escouade");
        squadName.setMaxLength(20);
        panel.add(squadName).width(220).height(32).padRight(8);

        TextButton join = new TextButton("Rejoindre", BrainOutClient.Skin, "button-green");
        join.addListener(new ClickOverListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                playSound(MenuSound.select);

                String name = squadName.getText().trim();
                if (!name.isEmpty())
                {
                    BrainOutClient.ClientController.sendTCP(new ChatSendMsg("/squad " + name, ChatSendMsg.Mode.everyone));
                }
            }
        });
        panel.add(join).width(130).height(32).padRight(8);

        TextButton leave = new TextButton("Quitter", BrainOutClient.Skin, "button-default");
        leave.addListener(new ClickOverListener()
        {
            @Override
            public void clicked(InputEvent event, float x, float y)
            {
                playSound(MenuSound.select);
                BrainOutClient.ClientController.sendTCP(new ChatSendMsg("/squad", ChatSendMsg.Mode.everyone));
            }
        });
        panel.add(leave).width(110).height(32).row();

        Label hint = new Label("Meme nom = meme escouade : allies, reapparition ensemble", BrainOutClient.Skin, "title-gray");
        panel.add(hint).colspan(3).right().padTop(6);

        panel.pack();
        panel.setPosition(BrainOutClient.getWidth() - panel.getWidth() - 30,
            BrainOutClient.getHeight() - panel.getHeight() - 20);

        return panel;
    }

    private static String currentSquad()
    {
        RemoteClient me = BrainOutClient.ClientController.getMyRemoteClient();
        if (me == null)
            return null;

        String party = me.getPartyId();
        if (party == null || !party.startsWith("squad-"))
            return null;

        return party.substring(6);
    }

    @Override
    public void act(float delta)
    {
        super.act(delta);

        if (squadLabel != null)
        {
            String squad = currentSquad();
            squadLabel.setText(squad != null ? "Escouade : " + squad : "Pas d'escouade");
        }
    }

    @Override
    public boolean lockInput()
    {
        return true;
    }

    @Override
    public boolean escape()
    {
        pop();
        return true;
    }

    @Override
    public boolean keyDown(int keyCode)
    {
        // typing a squad name must not close the map
        boolean typing = getKeyboardFocus() instanceof TextField;
        int mapKey = BrainOutClient.ClientSett.getControls().getKeyCode(KeyProperties.Keys.worldMap, Input.Keys.M);

        if (!typing && keyCode == mapKey)
        {
            pop();
            return true;
        }

        return super.keyDown(keyCode);
    }

    private static Texture terrain(Map map)
    {
        Texture texture = TERRAIN.get(map.getDimension());
        if (texture != null)
            return texture;

        if (map.getBlocks() == null)
            return null;

        int width = map.getWidth(), height = map.getHeight();
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        pixmap.setColor(GROUND);

        for (int j = 0, t = map.getBlocks().getBlockHeight(); j < t; j++)
        {
            for (int i = 0, k = map.getBlocks().getBlockWidth(); i < k; i++)
            {
                ChunkData chunk = map.getChunk(i, j);
                if (chunk == null)
                    continue;

                for (int j1 = 0; j1 < Constants.Core.CHUNK_SIZE; j1++)
                {
                    for (int i1 = 0; i1 < Constants.Core.CHUNK_SIZE; i1++)
                    {
                        BlockData block = chunk.get(i1, j1, Constants.Layers.BLOCK_LAYER_FOREGROUND);

                        if (block != null)
                        {
                            // pixmaps go top to bottom, the world bottom to top
                            pixmap.drawPixel(i * Constants.Core.CHUNK_SIZE + i1,
                                height - 1 - (j * Constants.Core.CHUNK_SIZE + j1));
                        }
                    }
                }
            }
        }

        texture = new Texture(pixmap);
        pixmap.dispose();
        TERRAIN.put(map.getDimension(), texture);
        return texture;
    }

    private static class Strip
    {
        Map map;
        String name;
        float x, y, scale;
    }

    private class MapView extends Actor
    {
        @Override
        public void draw(Batch batch, float parentAlpha)
        {
            float margin = 60;
            float width = getWidth() - margin * 2;

            Array<Strip> strips = new Array<>();
            float totalHeight = 0;

            for (int i = 0; i < AREAS.length; i++)
            {
                Map map = Map.Get(AREAS[i]);
                if (map == null)
                    continue;

                Strip strip = new Strip();
                strip.map = map;
                strip.name = AREA_NAMES[i];
                strip.scale = width / map.getWidth();
                totalHeight += map.getHeight() * strip.scale + 40;
                strips.add(strip);
            }

            // shrink everything if the areas do not fit the screen height
            float fit = Math.min(1, (getHeight() - margin * 2) / Math.max(totalHeight, 1));
            float y = getHeight() - margin;

            for (Strip strip : strips)
            {
                strip.scale *= fit;
                y -= 30;
                strip.y = y - strip.map.getHeight() * strip.scale;
                strip.x = margin + (width - strip.map.getWidth() * strip.scale) / 2;
                y = strip.y - 10;
            }

            // background
            batch.end();
            ShapeRenderer shapes = BrainOutClient.ShapeRenderer;
            shapes.setProjectionMatrix(batch.getProjectionMatrix());
            com.badlogic.gdx.Gdx.gl.glEnable(GL20.GL_BLEND);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(BACKGROUND);
            shapes.rect(0, 0, getWidth(), getHeight());
            shapes.end();
            batch.begin();

            BitmapFont font = BrainOutClient.Skin.getFont("ingame");
            Color fontColor = new Color(font.getColor());

            for (Strip strip : strips)
            {
                Texture texture = terrain(strip.map);

                if (texture != null)
                {
                    batch.setColor(Color.WHITE);
                    batch.draw(texture, strip.x, strip.y,
                        strip.map.getWidth() * strip.scale, strip.map.getHeight() * strip.scale);
                }

                font.setColor(Color.WHITE);
                font.draw(batch, strip.name, strip.x, strip.y + strip.map.getHeight() * strip.scale + 22);
            }

            // building doors and markers
            batch.end();
            shapes.begin(ShapeRenderer.ShapeType.Filled);

            for (Strip strip : strips)
            {
                shapes.setColor(DOOR);
                for (ActiveData door : strip.map.getActivesForTag(Constants.ActiveTags.PORTAL, false))
                {
                    shapes.rect(strip.x + door.getX() * strip.scale - 1, strip.y + door.getY() * strip.scale - 1, 3, 3);
                }
            }

            SquadPositionsMsg positions = SquadHud.getPositions();

            if (positions != null)
            {
                Strip mine = find(strips, positions.selfMapDimension);
                if (mine != null)
                {
                    shapes.setColor(ME);
                    shapes.circle(mine.x + positions.selfMapX * mine.scale, mine.y + positions.selfMapY * mine.scale, 6);
                }

                for (int i = 0; positions.ids != null && i < positions.ids.length; i++)
                {
                    Strip strip = find(strips, positions.mapDimensions[i]);
                    if (strip == null || positions.states[i] == 2)
                        continue;

                    shapes.setColor(positions.states[i] == 1 ? WOUNDED : MATE);
                    shapes.circle(strip.x + positions.mapX[i] * strip.scale, strip.y + positions.mapY[i] * strip.scale, 5);
                }
            }

            shapes.end();
            batch.begin();

            if (positions != null)
            {
                Strip mine = find(strips, positions.selfMapDimension);
                if (mine != null)
                {
                    font.setColor(ME);
                    font.draw(batch, "Vous", mine.x + positions.selfMapX * mine.scale - 14,
                        mine.y + positions.selfMapY * mine.scale + 24);
                }

                for (int i = 0; positions.ids != null && i < positions.ids.length; i++)
                {
                    Strip strip = find(strips, positions.mapDimensions[i]);
                    if (strip == null || positions.states[i] == 2)
                        continue;

                    font.setColor(positions.states[i] == 1 ? WOUNDED : MATE);
                    font.draw(batch, positions.names[i], strip.x + positions.mapX[i] * strip.scale - 14,
                        strip.y + positions.mapY[i] * strip.scale - 10);
                }
            }

            font.setColor(Color.LIGHT_GRAY);
            font.draw(batch, "Echap : fermer   -   jaune : portes et entrees de batiments",
                margin, margin - 20);

            font.setColor(fontColor);
        }

        private Strip find(Array<Strip> strips, String dimension)
        {
            if (dimension == null)
                return null;

            for (Strip strip : strips)
            {
                if (strip.map.getDimension().equals(dimension))
                    return strip;
            }

            return null;
        }
    }
}
