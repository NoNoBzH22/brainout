package com.desertkun.brainout.client.states;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.desertkun.brainout.BrainOut;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.client.PrivateServer;
import com.desertkun.brainout.client.Updater;
import com.desertkun.brainout.menu.popups.OKInputPopup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class CSWaitForUser extends ControllerState
{
    private static final String LAST_ADDRESS_FILE = "last-server.txt";
    private static final String LAST_ACCOUNT_FILE = "last-account.txt";

    // must match AccountStore.TOKEN_PREFIX on the server
    private static final String ACCOUNT_TOKEN_PREFIX = "bo-account:";

    /** Sent to private servers in the hello message, see CSConnected. */
    public static String AccountToken = null;

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
            // a newer build of the game is offered first
            Updater.Check(this::askAddress);
        }
    }

    private void askAddress()
    {
        BrainOutClient.getInstance().topState().pushMenu(new OKInputPopup("Enter Server IP", readLocal(LAST_ADDRESS_FILE, "127.0.0.1"))
        {
            @Override
            public void ok()
            {
                String value = valueEdit.getText().trim();
                writeLocal(LAST_ADDRESS_FILE, value);

                PrivateServer.SetAddress(value);
                askAccount();
            }
        });
    }

    private void askAccount()
    {
        BrainOutClient.getInstance().topState().pushMenu(new OKInputPopup("Account name (a new name creates an account)",
            readLocal(LAST_ACCOUNT_FILE, ""))
        {
            @Override
            public void ok()
            {
                String account = valueEdit.getText().trim();
                writeLocal(LAST_ACCOUNT_FILE, account);

                askPassword(account);
            }
        });
    }

    private void askPassword(String account)
    {
        BrainOutClient.getInstance().topState().pushMenu(new OKInputPopup("Password for " + account, "")
        {
            @Override
            protected TextField newEdit(String value)
            {
                TextField edit = super.newEdit(value);
                edit.setPasswordCharacter('*');
                edit.setPasswordMode(true);
                return edit;
            }

            @Override
            public void ok()
            {
                AccountToken = ACCOUNT_TOKEN_PREFIX + account + ":" + hashPassword(account, valueEdit.getText());

                // everyone arrives in the lobby and picks a mode there; servers without a lobby
                // get the mode choice instead
                PrivateServer.Connect(PrivateServer.Mode.lobby, () ->
                    PrivateServer.Failed("Pas de lobby sur ce serveur, choisis directement un mode."));
            }
        });
    }

    // the password itself never leaves the game, the server only sees (and re-hashes) this digest
    private static String hashPassword(String account, String password)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("brainout:" + account.toLowerCase() + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static String readLocal(String fileName, String defaultValue)
    {
        try
        {
            FileHandle file = Gdx.files.local(fileName);
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

        return defaultValue;
    }

    private static void writeLocal(String fileName, String value)
    {
        try
        {
            Gdx.files.local(fileName).writeString(value, false, "UTF-8");
        }
        catch (Exception ignored)
        {
        }
    }
}
