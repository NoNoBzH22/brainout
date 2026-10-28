package com.desertkun.brainout.menu.impl;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.client.PrivateServer;
import com.desertkun.brainout.menu.Menu;
import com.desertkun.brainout.menu.ui.ClickOverListener;
import com.desertkun.brainout.menu.ui.MenuHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Statistics and ranking of everyone who plays on the private server, and where they play now.
 */
public class PrivateStatsMenu extends Menu
{
    private interface Value
    {
        float of(JSONObject player);
    }

    private static class Column
    {
        final String title;
        final Value value;
        final boolean decimal;

        Column(String title, Value value, boolean decimal)
        {
            this.title = title;
            this.value = value;
            this.decimal = decimal;
        }
    }

    private static float KD(JSONObject player)
    {
        return player.optLong("kills") / (float) Math.max(1, player.optLong("deaths"));
    }

    private static final Column[] COLUMNS = {
        new Column("Niveau", p -> p.optLong("level") * 1e9f + p.optLong("score"), false),
        new Column("Kills", p -> p.optLong("kills"), false),
        new Column("Morts", p -> p.optLong("deaths"), false),
        new Column("K/D", PrivateStatsMenu::KD, true),
        new Column("Tirs tete", p -> p.optLong("headshots"), false),
        new Column("Victoires", p -> p.optLong("won"), false),
        new Column("Kills MO", p -> p.optLong("fpKills"), false),
        new Column("Sorties MO", p -> p.optLong("fpExits"), false),
        new Column("Succes", p -> p.optLong("achievements"), false),
        new Column("Heures", p -> p.optLong("minutes") / 60f, true),
    };

    private final Table rows = new Table();
    private final Label summary = new Label("Chargement...", BrainOutClient.Skin, "title-small");
    private JSONArray players;
    private String me;
    private int sortColumn = 0;
    private boolean onTop = true;

    @Override
    public void onInit()
    {
        super.onInit();

        MenuHelper.AddCloseButton(this, this::close);
        load();
    }

    @Override
    public Table createUI()
    {
        Table data = new Table();

        data.add(new Label("Classement du serveur", BrainOutClient.Skin, "title-yellow")).padTop(16).row();

        summary.setAlignment(Align.center);
        data.add(summary).pad(8).row();

        data.add(new Label("Clique sur une colonne pour trier. MO = monde ouvert.",
            BrainOutClient.Skin, "title-gray")).padBottom(8).row();

        ScrollPane pane = new ScrollPane(rows, BrainOutClient.Skin, "scroll-default");
        pane.setScrollingDisabled(true, false);
        pane.setFadeScrollBars(false);
        rows.align(Align.top);

        data.add(pane).width(1100).expandY().fillY().padBottom(32).row();
        setScrollFocus(pane);

        return data;
    }

    private void load()
    {
        String url = PrivateServer.StatsURL();

        if (url == null)
        {
            summary.setText("Classement indisponible sur ce serveur");
            return;
        }

        new Thread(() ->
        {
            JSONObject result;

            try
            {
                result = new JSONObject(PrivateServer.HttpGet(url));
            }
            catch (Exception e)
            {
                Gdx.app.postRunnable(() -> summary.setText("Classement indisponible (" + e.getMessage() + ")"));
                return;
            }

            Gdx.app.postRunnable(() ->
            {
                players = result.optJSONArray("players");
                me = result.optString("me", "");
                renderRanking();
            });
        }, "private-stats").start();
    }

    private void renderRanking()
    {
        rows.clear();

        if (players == null || players.length() == 0)
        {
            summary.setText("Personne n'a encore de statistiques");
            return;
        }

        Array<JSONObject> sorted = new Array<>();
        for (int i = 0; i < players.length(); i++)
        {
            sorted.add(players.getJSONObject(i));
        }

        Value value = COLUMNS[sortColumn].value;
        sorted.sort((a, b) -> Float.compare(value.of(b), value.of(a)));

        // header: the columns sort the ranking
        Table header = new Table();
        header.add(new Label("#", BrainOutClient.Skin, "title-gray")).width(40);
        header.add(new Label("Joueur", BrainOutClient.Skin, "title-gray")).width(260).left();

        for (int c = 0; c < COLUMNS.length; c++)
        {
            final int column = c;
            TextButton button = new TextButton(COLUMNS[c].title, BrainOutClient.Skin,
                c == sortColumn ? "button-green" : "button-default");
            button.getLabel().setFontScale(0.8f);
            button.addListener(new ClickOverListener()
            {
                @Override
                public void clicked(InputEvent event, float x, float y)
                {
                    playSound(MenuSound.select);
                    sortColumn = column;
                    renderRanking();
                }
            });
            header.add(button).size(78, 32).pad(1);
        }

        rows.add(header).expandX().fillX().padBottom(4).row();

        int online = 0;

        for (int i = 0; i < sorted.size; i++)
        {
            JSONObject player = sorted.get(i);
            boolean mine = player.optString("name").equalsIgnoreCase(me);
            String where = player.optString("online", "");

            if (!where.isEmpty())
                online++;

            if (mine)
            {
                summary.setText("Tu es " + (i + 1) + (i == 0 ? "er" : "e") + " sur " + sorted.size +
                    " en " + COLUMNS[sortColumn].title + "  -  niveau " + player.optLong("level") +
                    ", K/D " + format(KD(player), true));
            }

            String style = mine ? "title-green" : i < 3 ? "title-yellow" : "title-small";

            Button row = new Button(BrainOutClient.Skin,
                i == 0 ? "button-row-red" : i % 2 == 0 ? "button-row-dark-blue" : "button-row-border-blue");
            row.setTouchable(Touchable.disabled);

            row.add(new Label(String.valueOf(i + 1), BrainOutClient.Skin, style)).width(40);

            Table name = new Table();
            name.add(new Label(player.optString("name"), BrainOutClient.Skin, style)).left().row();
            if (!where.isEmpty())
            {
                name.add(new Label("en ligne : " + ModeTitle(where), BrainOutClient.Skin, "title-green"))
                    .left().row();
            }
            row.add(name).width(260).left();

            for (Column column : COLUMNS)
            {
                Label label = new Label(column == COLUMNS[0] ?
                    String.valueOf(player.optLong("level")) : format(column.value.of(player), column.decimal),
                    BrainOutClient.Skin, style);
                label.setAlignment(Align.center);
                row.add(label).width(80);
            }

            rows.add(row).expandX().fillX().height(48).row();
        }

        if (online > 0)
        {
            summary.setText(summary.getText() + "\n" + online + " joueur(s) en ligne");
        }
    }

    private static String ModeTitle(String mode)
    {
        for (PrivateServer.Mode m : PrivateServer.Mode.values())
        {
            if (m.name().equals(mode))
                return m.title;
        }

        return mode;
    }

    private static String format(float value, boolean decimal)
    {
        return decimal ? String.format("%.2f", value) : String.valueOf((long) value);
    }

    @Override
    protected TextureRegion getBackground()
    {
        return BrainOutClient.getRegion("bg-ingame");
    }

    @Override
    protected MenuAlign getMenuAlign()
    {
        return MenuAlign.fill;
    }

    @Override
    public boolean stayOnTop()
    {
        return onTop;
    }

    @Override
    public boolean escape()
    {
        close();
        return true;
    }

    private void close()
    {
        onTop = false;
        pop();
    }

    @Override
    public boolean lockInput()
    {
        return true;
    }
}
