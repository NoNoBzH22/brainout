package com.desertkun.brainout.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.utils.ArrayMap;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.client.states.CSError;
import com.desertkun.brainout.common.enums.DisconnectReason;
import com.desertkun.brainout.menu.Menu;
import com.desertkun.brainout.menu.Popup;
import com.desertkun.brainout.menu.ui.ClickOverListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A private server runs one game per mode, each on its own ports: base port + offset
 * (udp and http follow the tcp port). Players arrive in the lobby and pick a mode there.
 */
public class PrivateServer
{
    public enum Mode
    {
        free("Monde ouvert", 0),
        deathmatch("Melee generale", 10),
        ctf("Capture du drapeau", 20),
        lobby("Lobby", 30);

        public final String title;
        public final int offset;

        Mode(String title, int offset)
        {
            this.title = title;
            this.offset = offset;
        }
    }

    public static final int DEFAULT_PORT = 36555;

    private static String host;
    private static int basePort = DEFAULT_PORT;

    public static boolean IsActive()
    {
        return host != null;
    }

    /** "host" or "host:port" as typed by the player */
    public static void SetAddress(String address)
    {
        host = address;
        basePort = DEFAULT_PORT;

        int colon = address.lastIndexOf(':');
        if (colon > 0)
        {
            try
            {
                basePort = Integer.parseInt(address.substring(colon + 1));
                host = address.substring(0, colon);
            }
            catch (NumberFormatException ignored)
            {
            }
        }
    }

    // the HTTP side of the game the player is in, and the session it gave at login
    private static String httpLocation;
    private static String session;

    public static void SetSession(String location, String token)
    {
        httpLocation = location;
        session = token;
    }

    public static String StatsURL()
    {
        if (httpLocation == null || session == null)
            return null;

        try
        {
            return httpLocation + "/private/stats?access_token=" + URLEncoder.encode(session, "UTF-8");
        }
        catch (IOException e)
        {
            return null;
        }
    }

    public static String HttpGet(String url) throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);

        try
        {
            if (connection.getResponseCode() != 200)
                throw new IOException("HTTP " + connection.getResponseCode());

            try (InputStream in = connection.getInputStream())
            {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1)
                {
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        finally
        {
            connection.disconnect();
        }
    }

    public static void Connect(Mode mode, Runnable onConnectionFailed)
    {
        int port = basePort + mode.offset;

        Gdx.app.postRunnable(() -> BrainOutClient.ClientController.connect(
            host, port, port + 1, port + 2, null, mode == Mode.lobby, -1, onConnectionFailed));
    }

    /** Leaves the current game and joins the given mode on the same server. */
    public static void SwitchTo(Mode mode)
    {
        Runnable failed = () -> Failed("Impossible de rejoindre : " + mode.title +
            ". Ce mode est-il active sur le serveur ?");

        if (BrainOutClient.ClientController.isConnected())
        {
            BrainOutClient.ClientController.disconnect(DisconnectReason.leave, () -> Connect(mode, failed));
        }
        else
        {
            Connect(mode, failed);
        }
    }

    /** Shows the error, goes back to the main menu and lets the player pick another mode. */
    public static void Failed(String message)
    {
        BrainOutClient.ClientController.setState(new CSError(message, () ->
            BrainOutClient.getInstance().topState().pushMenu(ModesPopup(Mode.values()))));
    }

    /** The mode choice shown by the lobby portals and the pause menu. */
    public static Popup ModesPopup(Mode... modes)
    {
        return new Popup("Choisis un mode")
        {
            {
                ArrayMap<String, PopupButtonStyle> buttons = new ArrayMap<>();

                for (Mode mode : modes)
                {
                    buttons.put(mode.title, new PopupButtonStyle(new ClickOverListener()
                    {
                        @Override
                        public void clicked(InputEvent event, float x, float y)
                        {
                            Menu.playSound(Menu.MenuSound.select);
                            pop();
                            SwitchTo(mode);
                        }
                    }));
                }

                buttons.put("Annuler", new PopupButtonStyle(new ClickOverListener()
                {
                    @Override
                    public void clicked(InputEvent event, float x, float y)
                    {
                        Menu.playSound(Menu.MenuSound.select);
                        pop();
                    }
                }));

                setButtons(buttons);
            }
        };
    }
}
