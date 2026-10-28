package com.desertkun.brainout.server.http;

import com.desertkun.brainout.online.LocalMarket;
import com.desertkun.brainout.online.LocalStats;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class ContentHttpServer
{
    private HttpServer server;

    public ContentHttpServer(int port) throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

    }

    public void start()
    {
        server.createContext("/map", new MapHandler());
        server.createContext("/map-dimensions", new MapDimensionHandler());
        server.createContext("/add-image", new AddImageExtensionHandler());
        server.createContext("/upload-map", new UploadMapHandler());
        // stash storage of private servers, in place of the official market service
        server.createContext(LocalMarket.PATH, new LocalMarket());
        // ranking of the private server's players
        server.createContext(LocalStats.PATH, new LocalStats());

        server.setExecutor(Executors.newFixedThreadPool(4));

        server.start();
    }

    public void stop()
    {
        server.stop(0);
    }
}
