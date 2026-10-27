package com.desertkun.brainout.common.msg.server;

import com.desertkun.brainout.common.enums.DisconnectReason;

public class ServerDisconnect
{
    public DisconnectReason reason;
    // optional explanation shown to the player, e.g. a wrong password
    public String message;

    public ServerDisconnect() {}
    public ServerDisconnect(DisconnectReason reason)
    {
        this.reason = reason;
    }

    public ServerDisconnect(DisconnectReason reason, String message)
    {
        this.reason = reason;
        this.message = message;
    }
}
