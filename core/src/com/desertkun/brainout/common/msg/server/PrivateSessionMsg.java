package com.desertkun.brainout.common.msg.server;

/**
 * Sent by a private server after a successful account login: the token the game uses to reach
 * the server's own HTTP services (the stash), in place of the official online services.
 */
public class PrivateSessionMsg
{
    public String token;
    // path of the market service on the server's HTTP port
    public String marketPath;

    public PrivateSessionMsg() {}

    public PrivateSessionMsg(String token, String marketPath)
    {
        this.token = token;
        this.marketPath = marketPath;
    }
}
