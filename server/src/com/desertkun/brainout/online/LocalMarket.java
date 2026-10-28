package com.desertkun.brainout.online;

import com.esotericsoftware.minlog.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Stands in for the official "market" service on private servers. The game keeps the player's
 * stash, apartment items and similar storage there; both the game and the server talk to it over
 * HTTP with the session token issued at login (see AccountStore.newSession).
 *
 * Each account's items live in DATA_DIR/<account>/market-<market>.json. Updates are batches of
 * amount changes applied all or nothing, and an item's amount never goes below zero, so nothing
 * can be taken out that was not put in. Player to player orders are not supported.
 *
 * Mounted on the server's HTTP port under /anthill/market, it mirrors the official API:
 *   GET  /markets/{market}                 settings
 *   GET  /markets/{market}/items           all items of the player
 *   POST /markets/{market}/items           items=[{name, amount, payload}] amount changes
 *   GET  /markets/{market}/items/{item}    amount of the item with the given payload
 *   POST /markets/{market}/items/{item}    payload, amount: one amount change
 *   GET  /markets/{market}/orders[/my]     always empty
 */
public class LocalMarket implements HttpHandler
{
    public static final String PATH = "/anthill/market";

    private static final Pattern MARKET_NAME = Pattern.compile("^[A-Za-z0-9_\\-]{1,32}$");

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
            Map<String, String> form = "POST".equalsIgnoreCase(exchange.getRequestMethod()) ?
                parseForm(readBody(exchange.getRequestBody())) : new HashMap<>();

            String account = AccountStore.Get().getSessionAccount(query.get("access_token"));
            if (account == null)
            {
                respond(exchange, 403, new JSONObject().put("error", "bad session"));
                return;
            }

            String path = exchange.getRequestURI().getPath().substring(PATH.length());
            String[] parts = path.split("/");

            // "", "markets", market, ...
            if (parts.length < 3 || !parts[1].equals("markets") || !MARKET_NAME.matcher(parts[2]).matches())
            {
                respond(exchange, 404, new JSONObject());
                return;
            }

            String market = parts[2];

            if (parts.length == 3)
            {
                respond(exchange, 200, new JSONObject().put("settings", new JSONObject()));
                return;
            }

            switch (parts[3])
            {
                case "items":
                {
                    if (parts.length == 4)
                    {
                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()))
                        {
                            JSONArray changes = new JSONArray(form.getOrDefault("items", "[]"));
                            boolean ok = update(account, market, changes);
                            respond(exchange, ok ? 200 : 409, new JSONObject());
                        }
                        else
                        {
                            respond(exchange, 200, new JSONObject().put("items", list(account, market)));
                        }
                    }
                    else
                    {
                        String item = URLDecoder.decode(parts[4], "UTF-8");

                        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()))
                        {
                            JSONArray changes = new JSONArray().put(new JSONObject()
                                .put("name", item)
                                .put("amount", Integer.parseInt(form.getOrDefault("amount", "0")))
                                .put("payload", new JSONObject(form.getOrDefault("payload", "{}"))));
                            boolean ok = update(account, market, changes);
                            respond(exchange, ok ? 200 : 409, new JSONObject());
                        }
                        else
                        {
                            JSONObject payload = new JSONObject(query.getOrDefault("payload", "{}"));
                            respond(exchange, 200, new JSONObject().put("amount", amount(account, market, item, payload)));
                        }
                    }
                    return;
                }
                case "orders":
                {
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod()))
                    {
                        respond(exchange, 200, new JSONObject().put("orders", new JSONArray()));
                    }
                    else
                    {
                        respond(exchange, 404, new JSONObject().put("error", "orders are not supported"));
                    }
                    return;
                }
                default:
                {
                    respond(exchange, 404, new JSONObject());
                }
            }
        }
        catch (Exception e)
        {
            if (Log.ERROR) Log.error("Market request failed: " + e);
            respond(exchange, 400, new JSONObject().put("error", "bad request"));
        }
    }

    // stored as {"items": [{name, amount, payload}]}, one entry per name and payload
    private static JSONArray load(String account, String market)
    {
        JSONObject data = AccountStore.Get().loadData(account, "market-" + market + ".json");
        return data != null ? data.optJSONArray("items") != null ? data.getJSONArray("items") : new JSONArray() : new JSONArray();
    }

    private static synchronized JSONArray list(String account, String market)
    {
        return load(account, market);
    }

    private static synchronized int amount(String account, String market, String item, JSONObject payload)
    {
        String key = key(item, payload);
        JSONArray items = load(account, market);

        for (int i = 0; i < items.length(); i++)
        {
            JSONObject entry = items.getJSONObject(i);
            if (key(entry.getString("name"), entry.optJSONObject("payload")).equals(key))
                return entry.optInt("amount", 0);
        }

        return 0;
    }

    private static synchronized boolean update(String account, String market, JSONArray changes)
    {
        JSONArray items = load(account, market);

        // name + payload -> entry, in their original order
        Map<String, JSONObject> byKey = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++)
        {
            JSONObject entry = items.getJSONObject(i);
            byKey.put(key(entry.getString("name"), entry.optJSONObject("payload")), entry);
        }

        for (int i = 0; i < changes.length(); i++)
        {
            JSONObject change = changes.getJSONObject(i);
            String name = change.getString("name");
            JSONObject payload = change.optJSONObject("payload");
            if (payload == null)
            {
                payload = new JSONObject();
            }

            int delta = change.optInt("amount", 0);
            String key = key(name, payload);

            JSONObject entry = byKey.get(key);
            int have = entry != null ? entry.optInt("amount", 0) : 0;

            if (have + delta < 0)
            {
                if (Log.WARN) Log.warn("Market " + market + " of " + account + ": not enough " + name);
                return false;
            }

            if (entry == null)
            {
                entry = new JSONObject().put("name", name).put("payload", payload).put("amount", 0);
                byKey.put(key, entry);
            }

            entry.put("amount", have + delta);
        }

        JSONArray result = new JSONArray();
        for (JSONObject entry : byKey.values())
        {
            if (entry.optInt("amount", 0) > 0)
            {
                result.put(entry);
            }
        }

        AccountStore.Get().saveData(account, "market-" + market + ".json", new JSONObject().put("items", result));
        return true;
    }

    // items are told apart by name and payload; payload keys are sorted so the order does not matter
    private static String key(String name, JSONObject payload)
    {
        return name + "|" + (payload != null ? canonical(payload) : "{}");
    }

    private static Object canonical(Object value)
    {
        if (value instanceof JSONObject)
        {
            JSONObject object = ((JSONObject) value);
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Iterator<String> it = object.keys(); it.hasNext(); )
            {
                String k = it.next();
                sorted.put(k, canonical(object.get(k)));
            }

            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, Object> entry : sorted.entrySet())
            {
                if (out.length() > 1)
                    out.append(",");
                out.append(JSONObject.quote(entry.getKey())).append(":").append(entry.getValue());
            }
            return out.append("}").toString();
        }

        if (value instanceof JSONArray)
        {
            JSONArray array = ((JSONArray) value);
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < array.length(); i++)
            {
                parts.add(String.valueOf(canonical(array.get(i))));
            }
            return "[" + String.join(",", parts) + "]";
        }

        if (value instanceof String)
        {
            return JSONObject.quote(((String) value));
        }

        return String.valueOf(value);
    }

    private static String readBody(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0)
        {
            out.write(buffer, 0, read);
            if (out.size() > 4 * 1024 * 1024)
                throw new IOException("request too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseForm(String raw) throws IOException
    {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty())
            return result;

        for (String pair : raw.split("&"))
        {
            int eq = pair.indexOf('=');
            if (eq < 0)
                continue;

            result.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }

        return result;
    }

    private static void respond(HttpExchange exchange, int code, JSONObject body) throws IOException
    {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream out = exchange.getResponseBody())
        {
            out.write(data);
        }
    }
}
