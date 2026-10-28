package com.desertkun.brainout.common.msg.server;

/**
 * Where the other members of the player's squad are, sent every second on private servers.
 * Arrays are parallel, one entry per squad mate.
 */
public class SquadPositionsMsg
{
    public int[] ids;
    public String[] names;
    public String[] dimensions;
    public float[] x;
    public float[] y;
    // 0 alive, 1 wounded, 2 dead
    public byte[] states;

    public SquadPositionsMsg() {}

    public SquadPositionsMsg(int count)
    {
        ids = new int[count];
        names = new String[count];
        dimensions = new String[count];
        x = new float[count];
        y = new float[count];
        states = new byte[count];
    }
}
