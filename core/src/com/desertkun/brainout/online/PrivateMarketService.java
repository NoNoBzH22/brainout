package com.desertkun.brainout.online;

import org.anthillplatform.runtime.AnthillRuntime;
import org.anthillplatform.runtime.requests.Request;
import org.anthillplatform.runtime.services.LoginService;
import org.anthillplatform.runtime.services.MarketService;
import org.anthillplatform.runtime.services.Service;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The market service as served by a private game server (the server's LocalMarket).
 *
 * Writes go through the original implementation. Reads are redone here: the original only reads
 * a response whose header is spelled exactly "Content-Type", and the HTTP server built into Java
 * spells it "Content-type".
 */
public class PrivateMarketService extends MarketService
{
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "private-market");
        thread.setDaemon(true);
        return thread;
    });

    public PrivateMarketService(AnthillRuntime runtime, String location)
    {
        super(runtime, location);
    }

    /** Makes MarketService.Get() return a private market at the given location. */
    @SuppressWarnings("unchecked")
    public static void Install(AnthillRuntime runtime, String location)
    {
        try
        {
            Field field = AnthillRuntime.class.getDeclaredField("services");
            field.setAccessible(true);
            ((Map<String, Service>) field.get(runtime)).put(ID, new PrivateMarketService(runtime, location));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot install the private market service", e);
        }
    }

    private interface ResultHandler
    {
        void handle(Request.Result result, JSONObject response);
    }

    private void get(String path, LoginService.AccessToken accessToken, String extraQuery, ResultHandler handler)
    {
        EXECUTOR.execute(() ->
        {
            try
            {
                String query = "access_token=" + URLEncoder.encode(accessToken != null ? accessToken.get() : "", "UTF-8");
                if (extraQuery != null)
                {
                    query += "&" + extraQuery;
                }

                HttpURLConnection connection = (HttpURLConnection) new URL(getLocation() + path + "?" + query).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);

                int status = connection.getResponseCode();
                InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (in != null)
                {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0)
                    {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                }

                JSONObject response = null;
                try
                {
                    response = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
                catch (Exception ignored)
                {
                }

                handler.handle(toResult(status), response);
            }
            catch (Exception e)
            {
                handler.handle(Request.Result.failed, null);
            }
        });
    }

    private static Request.Result toResult(int status)
    {
        if (status >= 200 && status < 300)
            return Request.Result.success;

        switch (status)
        {
            case 400: return Request.Result.badRequest;
            case 403: return Request.Result.forbidden;
            case 404: return Request.Result.notFound;
            case 409: return Request.Result.conflict;
            default: return Request.Result.failed;
        }
    }

    @Override
    public void getMarketSettings(String marketName, LoginService.AccessToken accessToken, GetMarketSettingsCallback callback)
    {
        get("/markets/" + marketName, accessToken, null, (result, response) ->
            callback.complete(null, result, response != null ? response.optJSONObject("settings") : null));
    }

    @Override
    public void getMarketItems(String marketName, LoginService.AccessToken accessToken, GetMarketItemsCallback callback)
    {
        get("/markets/" + marketName + "/items", accessToken, null, (result, response) ->
        {
            JSONArray items = response != null ? response.optJSONArray("items") : null;

            if (result != Request.Result.success || items == null)
            {
                callback.complete(null, result == Request.Result.success ? Request.Result.failed : result, null);
                return;
            }

            List<MarketItemEntry> entries = new ArrayList<>();
            for (int i = 0; i < items.length(); i++)
            {
                JSONObject entry = items.optJSONObject(i);
                if (entry == null || entry.optString("name", null) == null || entry.optJSONObject("payload") == null)
                    continue;

                entries.add(new MarketItemEntry(entry.getString("name"), entry.optInt("amount", 1), entry.getJSONObject("payload")));
            }

            callback.complete(null, result, entries);
        });
    }

    @Override
    public void getMarketItem(String marketName, String item, JSONObject payload, LoginService.AccessToken accessToken,
                              GetMarketItemCallback callback)
    {
        String query;
        try
        {
            query = "payload=" + URLEncoder.encode(payload.toString(), "UTF-8");
        }
        catch (Exception e)
        {
            callback.complete(null, Request.Result.failed, 0);
            return;
        }

        get("/markets/" + marketName + "/items/" + item, accessToken, query, (result, response) ->
            callback.complete(null, result, response != null ? response.optInt("amount", 0) : 0));
    }

    // trading between players is not available on private servers

    @Override
    public void listOrders(String marketName, String ownerId, String giveItem, int giveAmount,
                           ListOrderComparison giveAmountComparison, JSONObject givePayload, String takeItem,
                           int takeAmount, ListOrderComparison takeAmountComparison, JSONObject takePayload,
                           MarkerEntriesOrder sortOrder, LoginService.AccessToken accessToken,
                           ListMarketOrdersCallback callback, int offset, int limit)
    {
        callback.complete(null, Request.Result.success, Collections.emptyList());
    }

    @Override
    public void listMyOrders(String marketName, LoginService.AccessToken accessToken, ListMarketOrdersCallback callback)
    {
        callback.complete(null, Request.Result.success, Collections.emptyList());
    }

    @Override
    public void getOrder(String marketName, String orderId, LoginService.AccessToken accessToken, GetMarketOrderCallback callback)
    {
        callback.complete(null, Request.Result.notFound, null);
    }

    @Override
    public void postOrder(String marketName, String giveItem, int giveAmount, JSONObject givePayload, String takeItem,
                          int takeAmount, JSONObject takePayload, int amount, JSONObject orderPayload,
                          java.util.Date deadline, LoginService.AccessToken accessToken, PostOrderCallback callback)
    {
        callback.complete(null, Request.Result.notFound, null, false);
    }

    @Override
    public void fulfillOrder(String marketName, String orderId, int fulfillAmount, LoginService.AccessToken accessToken,
                             PostOrderCallback callback)
    {
        callback.complete(null, Request.Result.notFound, null, false);
    }

    @Override
    public void deleteOrder(String marketName, String orderId, LoginService.AccessToken accessToken, DeleteOrderCallback callback)
    {
        callback.complete(null, Request.Result.notFound);
    }
}
