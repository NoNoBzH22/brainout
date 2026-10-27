package com.desertkun.brainout.client.states;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.desertkun.brainout.BrainOut;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.Constants;
import com.desertkun.brainout.menu.popups.AlertPopup;
import com.desertkun.brainout.menu.popups.InputPopup;
import com.desertkun.brainout.menu.popups.OKInputPopup;

public class CSWaitForUser extends ControllerState
{
    @Override
    public ID getID()
    {
        return ID.waitForUser;
    }

    @Override
    public void init()
    {

    }

    @Override
    public void release()
    {

    }

    public void proceed()
    {
        if (BrainOut.OnlineEnabled())
        {
            switchTo(new CSGetRegions());
        }
        else
        {
            BrainOutClient.getInstance().topState().pushMenu(new OKInputPopup("Enter Server IP", getLastAddress())
            {
                @Override
                public void ok()
                {
                    String value = getValue().trim();
                    saveLastAddress(value);

                    // "host" or "host:port"; udp and http ports follow the tcp one
                    String address = value;
                    int tcp = 36555;

                    int colon = value.lastIndexOf(':');
                    if (colon > 0)
                    {
                        try
                        {
                            tcp = Integer.parseInt(value.substring(colon + 1));
                            address = value.substring(0, colon);
                        }
                        catch (NumberFormatException ignored)
                        {
                        }
                    }

                    final String host = address;
                    final int port = tcp;

                    Gdx.app.postRunnable(() ->
                    {
                        BrainOutClient.ClientController.connect(
                            host,
                            port, port + 1, port + 2,
                            null,
                            false,
                            -1, () -> pushMenu(new AlertPopup("MENU_CONNECTION_ERROR")));
                    });
                }
            });
        }
    }

    private static final String LAST_ADDRESS_FILE = "last-server.txt";

    private static String getLastAddress()
    {
        try
        {
            FileHandle file = Gdx.files.local(LAST_ADDRESS_FILE);
            if (file.exists())
            {
                String value = file.readString("UTF-8").trim();
                if (!value.isEmpty())
                    return value;
            }
        }
        catch (Exception ignored)
        {
        }

        return "127.0.0.1";
    }

    private static void saveLastAddress(String value)
    {
        try
        {
            Gdx.files.local(LAST_ADDRESS_FILE).writeString(value, false, "UTF-8");
        }
        catch (Exception ignored)
        {
        }
    }
}
