package com.desertkun.brainout.menu.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.common.msg.server.SquadPositionsMsg;
import com.desertkun.brainout.data.ClientMap;
import com.desertkun.brainout.data.Map;
import com.desertkun.brainout.data.interfaces.Watcher;

/**
 * Shows where the squad mates are: a name tag with the distance over those on screen,
 * an arrow on the edge of the screen for those out of view, and a list naming the
 * area of those in another part of the world.
 */
public class SquadHud extends Actor
{
    private static SquadPositionsMsg Positions;
    private static long PositionsTime;

    private static final Color ALIVE = new Color(0.45f, 1.0f, 0.45f, 1.0f);
    private static final Color WOUNDED = new Color(1.0f, 0.65f, 0.2f, 1.0f);
    private static final Color DEAD = new Color(0.6f, 0.6f, 0.6f, 1.0f);
    private static final float MARGIN = 40;

    private final Vector2 screen = new Vector2();

    public static void setPositions(SquadPositionsMsg positions)
    {
        Positions = positions;
        PositionsTime = System.currentTimeMillis();
    }

    public SquadHud()
    {
        setTouchable(Touchable.disabled);
    }

    @Override
    public void draw(Batch batch, float parentAlpha)
    {
        super.draw(batch, parentAlpha);

        SquadPositionsMsg positions = Positions;
        if (positions == null || positions.ids == null || System.currentTimeMillis() - PositionsTime > 5000)
            return;

        Watcher watcher = Map.GetWatcher();
        String myDimension = Map.GetWatcherDimension();
        ClientMap map = Map.GetWatcherMap(ClientMap.class);

        if (watcher == null || myDimension == null || map == null)
            return;

        BitmapFont font = BrainOutClient.Skin.getFont("ingame");
        Color fontColor = new Color(font.getColor());

        float listY = getHeight() - 170;

        for (int i = 0; i < positions.ids.length; i++)
        {
            String name = positions.names[i];
            byte state = positions.states[i];

            if (state == 2)
            {
                font.setColor(DEAD);
                font.draw(batch, name + " - mort", 16, listY);
                listY -= 18;
                continue;
            }

            Color color = state == 1 ? WOUNDED : ALIVE;
            String suffix = state == 1 ? " (a terre)" : "";

            if (!myDimension.equals(positions.dimensions[i]))
            {
                font.setColor(color);
                font.draw(batch, name + " - " + describe(positions.dimensions[i]) + suffix, 16, listY);
                listY -= 18;
                continue;
            }

            int distance = (int) Vector2.dst(watcher.getWatchX(), watcher.getWatchY(), positions.x[i], positions.y[i]);

            // a bit above the head
            map.projectToScreen(positions.x[i], positions.y[i] + 3.5f, screen);

            font.setColor(color);

            boolean visible = screen.x >= MARGIN && screen.x <= getWidth() - MARGIN &&
                screen.y >= MARGIN && screen.y <= getHeight() - MARGIN;

            if (visible)
            {
                font.draw(batch, name + " " + distance + "m" + suffix, screen.x - 40, screen.y + 12);
            }
            else
            {
                float x = MathUtils.clamp(screen.x, MARGIN, getWidth() - MARGIN - 120);
                float y = MathUtils.clamp(screen.y, MARGIN, getHeight() - MARGIN);

                String arrow;
                if (screen.x < MARGIN)
                    arrow = "<< ";
                else if (screen.x > getWidth() - MARGIN)
                    arrow = ">> ";
                else if (screen.y > getHeight() - MARGIN)
                    arrow = "^ ";
                else
                    arrow = "v ";

                font.draw(batch, arrow + name + " " + distance + "m" + suffix, x, y);
            }
        }

        font.setColor(fontColor);
    }

    private static String describe(String dimension)
    {
        if (dimension == null || dimension.isEmpty())
            return "?";

        switch (dimension)
        {
            case "default":
                return "Plaine";
            case "forest":
                return "Foret";
            case "swamp2":
                return "Marais";
            case "intro":
                return "Depart";
            default:
                return "Batiment";
        }
    }
}
