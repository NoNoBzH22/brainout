package com.desertkun.brainout.common.msg.server;

/**
 * Where the player and the other members of their squad are, sent every second on private servers.
 * Arrays are parallel, one entry per squad mate.
 *
 * The "map" position is where to show someone on the world map: their position when they are
 * outside, the entrance of the building they are in otherwise. Empty dimension when unknown.
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
    public String[] mapDimensions;
    public float[] mapX;
    public float[] mapY;

    public String selfMapDimension;
    public float selfMapX, selfMapY;

    public SquadPositionsMsg() {}

    public SquadPositionsMsg(int count)
    {
        ids = new int[count];
        names = new String[count];
        dimensions = new String[count];
        x = new float[count];
        y = new float[count];
        states = new byte[count];
        mapDimensions = new String[count];
        mapX = new float[count];
        mapY = new float[count];
    }
}
