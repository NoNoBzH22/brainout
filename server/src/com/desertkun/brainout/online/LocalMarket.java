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
 * can be taken out that was not put in.
 *
 * Orders (players selling to each other) live in DATA_DIR/market-orders-<market>.json. Posting one
 * moves the items on sale out of the seller's storage until the order is fulfilled, cancelled or
 * expires; fulfilling takes the price from the buyer's storage and gives it to the seller.
 *
 * Mounted on the server's HTTP port under /anthill/market, it mirrors the official API:
 *   GET  /markets/{market}                 settings
 *   GET  /markets/{market}/items           all items of the player
 *   POST /markets/{market}/items           items=[{name, amount, payload}] amount changes
 *   GET  /markets/{market}/items/{item}    amount of the item with the given payload
 *   POST /markets/{market}/items/{item}    payload, amount: one amount change
 *   GET  /markets/{market}/orders          orders on sale, filtered like the official API
 *   POST /markets/{market}/orders          post an order
 *   GET  /markets/{market}/orders/my       the player's orders
 *   GET  /markets/{market}/orders/{id}     one order
 *   POST /markets/{market}/orders/{id}/fulfill   buy amount of it
 *   POST /markets/{market}/orders/{id}/delete    cancel it, the rest goes back to the seller
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
                    boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());

                    if (parts.length == 4)
                    {
                        if (post)
                        {
                            JSONObject order = postOrder(account, market, form);
                            if (order == null)
                            {
                                respond(exchange, 409, new JSONObject().put("error", "cannot post"));
                            }
                            else
                            {
                                respond(exchange, 200, new JSONObject()
                                    .put("order_id", order.getString("order_id"))
                                    .put("fulfilled_immediately", false));
                            }
                        }
                        else
                        {
                            respond(exchange, 200, new JSONObject().put("orders", listOrders(market, query, null)));
                        }
                        return;
                    }

                    String orderId = parts[4];

                    if (orderId.equals("my"))
                    {
                        respond(exchange, 200, new JSONObject().put("orders", listOrders(market, new HashMap<>(), account)));
                        return;
                    }

                    if (parts.length == 5 && !post)
                    {
                        JSONObject order = getOrder(market, orderId);
                        respond(exchange, order != null ? 200 : 404, order != null ? order : new JSONObject());
                        return;
                    }

                    if (parts.length == 6 && post && parts[5].equals("fulfill"))
                    {
                        int amount = Integer.parseInt(form.getOrDefault("amount", "1"));
                        boolean ok = fulfillOrder(account, market, orderId, amount);
                        respond(exchange, ok ? 200 : 409, new JSONObject()
                            .put("order_id", orderId).put("fulfilled_immediately", ok));
                        return;
                    }

                    if (parts.length == 6 && post && parts[5].equals("delete"))
                    {
                        boolean ok = deleteOrder(account, market, orderId);
                        respond(exchange, ok ? 200 : 404, new JSONObject());
                        return;
                    }

                    respond(exchange, 404, new JSONObject());
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

    private static final Object ORDERS_LOCK = new Object();
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private static String ordersFile(String market)
    {
        return "market-orders-" + market + ".json";
    }

    // all orders of a market, expired ones returned to their sellers first
    private static JSONArray loadOrders(String market)
    {
        JSONObject data = AccountStore.Get().loadShared(ordersFile(market));
        JSONArray orders = data != null && data.optJSONArray("orders") != null ? data.getJSONArray("orders") : new JSONArray();

        long now = System.currentTimeMillis();
        JSONArray alive = new JSONArray();
        boolean expired = false;

        for (int i = 0; i < orders.length(); i++)
        {
            JSONObject order = orders.getJSONObject(i);

            if (order.optLong("deadline_ms", Long.MAX_VALUE) < now)
            {
                returnToSeller(market, order);
                expired = true;
                continue;
            }

            alive.put(order);
        }

        if (expired)
        {
            saveOrders(market, alive);
        }

        return alive;
    }

    private static void saveOrders(String market, JSONArray orders)
    {
        AccountStore.Get().saveShared(ordersFile(market), new JSONObject().put("orders", orders));
    }

    private static void returnToSeller(String market, JSONObject order)
    {
        int rest = order.getInt("available") * order.getInt("give_amount");

        if (rest > 0)
        {
            update(order.getString("owner_id"), market, new JSONArray().put(new JSONObject()
                .put("name", order.getString("give_item"))
                .put("amount", rest)
                .put("payload", order.getJSONObject("give_payload"))));
        }
    }

    private static JSONObject postOrder(String account, String market, Map<String, String> form)
    {
        String giveItem = form.get("give_item");
        String takeItem = form.get("take_item");
        int giveAmount = Integer.parseInt(form.getOrDefault("give_amount", "0"));
        int takeAmount = Integer.parseInt(form.getOrDefault("take_amount", "0"));
        int ordersAmount = Integer.parseInt(form.getOrDefault("orders_amount", "0"));

        if (giveItem == null || takeItem == null || giveAmount <= 0 || takeAmount <= 0 ||
            ordersAmount <= 0 || ordersAmount > 100000)
            return null;

        JSONObject givePayload = new JSONObject(form.getOrDefault("give_payload", "{}"));
        JSONObject takePayload = new JSONObject(form.getOrDefault("take_payload", "{}"));
        JSONObject payload = new JSONObject(form.getOrDefault("payload", "{}"));

        long deadline = Long.MAX_VALUE;
        try
        {
            if (form.containsKey("deadline"))
            {
                // same (12 hour, local time) format the game sends; off by at most half a day
                deadline = new java.text.SimpleDateFormat("yyyy-MM-dd hh:mm:ss").parse(form.get("deadline")).getTime();
            }
        }
        catch (Exception ignored)
        {
            // keep the order until it is fulfilled or cancelled
        }

        synchronized (ORDERS_LOCK)
        {
            JSONArray orders = loadOrders(market);

            // the items on sale leave the seller's storage while the order is up
            if (!update(account, market, new JSONArray().put(new JSONObject()
                .put("name", giveItem).put("amount", -giveAmount * ordersAmount).put("payload", givePayload))))
                return null;

            byte[] id = new byte[12];
            RANDOM.nextBytes(id);

            JSONObject order = new JSONObject()
                .put("order_id", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id))
                .put("owner_id", account)
                .put("give_item", giveItem)
                .put("give_amount", giveAmount)
                .put("give_payload", givePayload)
                .put("take_item", takeItem)
                .put("take_amount", takeAmount)
                .put("take_payload", takePayload)
                .put("available", ordersAmount)
                .put("payload", payload)
                .put("deadline_ms", deadline);

            orders.put(order);
            saveOrders(market, orders);

            if (Log.INFO) Log.info("Market " + market + ": " + account + " sells " + ordersAmount + " x " +
                giveAmount + " " + giveItem + " for " + takeAmount + " " + takeItem + " each");

            return order;
        }
    }

    private static JSONObject getOrder(String market, String orderId)
    {
        synchronized (ORDERS_LOCK)
        {
            JSONArray orders = loadOrders(market);
            for (int i = 0; i < orders.length(); i++)
            {
                if (orders.getJSONObject(i).getString("order_id").equals(orderId))
                    return orders.getJSONObject(i);
            }
        }

        return null;
    }

    private static boolean compare(int value, String comparison, int to)
    {
        switch (comparison)
        {
            case ">": return value > to;
            case "<": return value < to;
            case ">=": return value >= to;
            case "<=": return value <= to;
            default: return value == to;
        }
    }

    private static JSONArray listOrders(String market, Map<String, String> query, String onlyOwner)
    {
        List<JSONObject> matching = new ArrayList<>();

        synchronized (ORDERS_LOCK)
        {
            JSONArray orders = loadOrders(market);

            for (int i = 0; i < orders.length(); i++)
            {
                JSONObject order = orders.getJSONObject(i);

                String owner = onlyOwner != null ? onlyOwner : query.get("owner_id");
                if (owner != null && !owner.equalsIgnoreCase(order.getString("owner_id")))
                    continue;
                if (query.containsKey("give_item") && !query.get("give_item").equals(order.getString("give_item")))
                    continue;
                if (query.containsKey("take_item") && !query.get("take_item").equals(order.getString("take_item")))
                    continue;
                if (query.containsKey("give_payload") &&
                    !canonical(new JSONObject(query.get("give_payload"))).equals(canonical(order.getJSONObject("give_payload"))))
                    continue;
                if (query.containsKey("give_amount") && !compare(order.getInt("give_amount"),
                    query.getOrDefault("give_amount_comparison", "="), Integer.parseInt(query.get("give_amount"))))
                    continue;
                if (query.containsKey("take_amount") && !compare(order.getInt("take_amount"),
                    query.getOrDefault("take_amount_comparison", "="), Integer.parseInt(query.get("take_amount"))))
                    continue;

                matching.add(order);
            }
        }

        String sortBy = query.get("sort_by");
        if (sortBy != null && (sortBy.equals("give_amount") || sortBy.equals("take_amount")))
        {
            boolean desc = "true".equals(query.get("sort_desc"));
            matching.sort((a, b) -> desc ? b.getInt(sortBy) - a.getInt(sortBy) : a.getInt(sortBy) - b.getInt(sortBy));
        }

        int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(query.getOrDefault("limit", "1000"));

        JSONArray result = new JSONArray();
        for (int i = Math.max(0, offset); i < matching.size() && result.length() < limit; i++)
        {
            result.put(matching.get(i));
        }

        return result;
    }

    private static boolean fulfillOrder(String buyer, String market, String orderId, int amount)
    {
        if (amount <= 0)
            return false;

        synchronized (ORDERS_LOCK)
        {
            JSONArray orders = loadOrders(market);

            for (int i = 0; i < orders.length(); i++)
            {
                JSONObject order = orders.getJSONObject(i);
                if (!order.getString("order_id").equals(orderId))
                    continue;

                String seller = order.getString("owner_id");
                if (seller.equalsIgnoreCase(buyer) || order.getInt("available") < amount)
                    return false;

                // the buyer pays and gets the items in one go, or nothing happens
                if (!update(buyer, market, new JSONArray()
                    .put(new JSONObject()
                        .put("name", order.getString("take_item"))
                        .put("amount", -order.getInt("take_amount") * amount)
                        .put("payload", order.getJSONObject("take_payload")))
                    .put(new JSONObject()
                        .put("name", order.getString("give_item"))
                        .put("amount", order.getInt("give_amount") * amount)
                        .put("payload", order.getJSONObject("give_payload")))))
                    return false;

                update(seller, market, new JSONArray().put(new JSONObject()
                    .put("name", order.getString("take_item"))
                    .put("amount", order.getInt("take_amount") * amount)
                    .put("payload", order.getJSONObject("take_payload"))));

                int left = order.getInt("available") - amount;
                if (left > 0)
                {
                    order.put("available", left);
                }
                else
                {
                    orders.remove(i);
                }

                saveOrders(market, orders);

                if (Log.INFO) Log.info("Market " + market + ": " + buyer + " bought " + amount + " of " + seller + "'s " +
                    order.getString("give_item"));
                return true;
            }
        }

        return false;
    }

    private static boolean deleteOrder(String account, String market, String orderId)
    {
        synchronized (ORDERS_LOCK)
        {
            JSONArray orders = loadOrders(market);

            for (int i = 0; i < orders.length(); i++)
            {
                JSONObject order = orders.getJSONObject(i);
                if (!order.getString("order_id").equals(orderId))
                    continue;

                if (!order.getString("owner_id").equalsIgnoreCase(account))
                    return false;

                returnToSeller(market, order);
                orders.remove(i);
                saveOrders(market, orders);
                return true;
            }
        }

        return false;
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
