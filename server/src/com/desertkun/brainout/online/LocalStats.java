package com.desertkun.brainout.online;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistics and ranking of everyone playing on a private server, shown by the game's stats menu.
 *
 * Mounted on the server's HTTP port under /private/stats, needs a session token (access_token):
 *   GET /private/stats   {"me": name, "players": [{name, online, stats...}]}
 *
 * The games of a server (lobby, open world, deathmatch...) run in separate processes sharing the
 * data directory: each one writes who plays on it to online-<mode>.json every few seconds, so any
 * of them can tell where everyone is.
 */
public class LocalStats implements HttpHandler
{
    public static final String PATH = "/private/stats";

    private static final String[] MODES = {"lobby", "free", "deathmatch", "ctf"};
    private static final long ONLINE_WRITE_PERIOD = 15000;
    private static final long ONLINE_EXPIRE = 45000;

    // stat name in the profile -> name sent to the game
    private static final String[][] STATS = {
        {"level", "level"},
        {"score", "score"},
        {"kills", "kills"},
        {"deaths", "deaths"},
        {"headshots", "headshots"},
        {"longshots", "longshots"},
        {"time-spent", "minutes"},
        {"games-won", "won"},
        {"games-lost", "lost"},
        {"freeplay-kills", "fpKills"},
        {"freeplay-exited", "fpExits"},
        {"kill-streaks-5", "streaks"},
        {"ru", "ru"},
    };

    private static final Set<String> online = ConcurrentHashMap.newKeySet();
    private static Timer timer;

    public static void PlayerJoined(String account)
    {
        online.add(account);
        WriteOnline();
    }

    public static void PlayerLeft(String account)
    {
        online.remove(account);
        WriteOnline();
    }

    private static String Mode()
    {
        String mode = System.getenv("PRIVATE_MODE");
        return mode != null && !mode.isEmpty() ? mode : "free";
    }

    public static synchronized void StartHeartbeat()
    {
        if (timer != null)
            return;

        timer = new Timer("online-players", true);
        timer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                WriteOnline();
            }
        }, 0, ONLINE_WRITE_PERIOD);
    }

    private static void WriteOnline()
    {
        JSONObject data = new JSONObject();
        data.put("updated", System.currentTimeMillis());
        data.put("players", new JSONArray(online));
        AccountStore.Get().saveShared("online-" + Mode() + ".json", data);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            String token = null;
            String query = exchange.getRequestURI().getRawQuery();

            if (query != null)
            {
                for (String pair : query.split("&"))
                {
                    if (pair.startsWith("access_token="))
                    {
                        token = URLDecoder.decode(pair.substring("access_token=".length()), "UTF-8");
                    }
                }
            }

            AccountStore accounts = AccountStore.Get();
            String me = accounts.getSessionAccount(token);

            if (me == null)
            {
                respond(exchange, 403, new JSONObject().put("error", "bad session"));
                return;
            }

            respond(exchange, 200, new JSONObject()
                .put("me", accounts.getDisplayName(me))
                .put("players", players(accounts)));
        }
        catch (Exception e)
        {
            respond(exchange, 500, new JSONObject().put("error", String.valueOf(e.getMessage())));
        }
    }

    private static JSONArray players(AccountStore accounts)
    {
        // where everyone plays right now
        JSONObject where = new JSONObject();
        long now = System.currentTimeMillis();

        for (String mode : MODES)
        {
            JSONObject data = accounts.loadShared("online-" + mode + ".json");
            if (data == null || now - data.optLong("updated", 0) > ONLINE_EXPIRE)
                continue;

            JSONArray names = data.optJSONArray("players");
            if (names == null)
                continue;

            for (int i = 0; i < names.length(); i++)
            {
                where.put(names.getString(i).toLowerCase(), mode);
            }
        }

        JSONArray result = new JSONArray();
        List<Object[]> profiles = accounts.listProfiles();

        for (Object[] entry : profiles)
        {
            String name = (String) entry[0];
            JSONObject profile = (JSONObject) entry[1];
            JSONObject stats = profile.optJSONObject("stats");
            if (stats == null)
                stats = new JSONObject();

            JSONObject player = new JSONObject();
            player.put("name", name);
            player.put("online", where.optString(name.toLowerCase(), ""));

            for (String[] stat : STATS)
            {
                player.put(stat[1], (long) stats.optDouble(stat[0], 0));
            }

            int achievements = 0;
            JSONObject items = profile.optJSONObject("items");
            if (items != null)
            {
                for (String key : items.keySet())
                {
                    if (key.startsWith("achievement-"))
                        achievements++;
                }
            }
            player.put("achievements", achievements);

            result.put(player);
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
