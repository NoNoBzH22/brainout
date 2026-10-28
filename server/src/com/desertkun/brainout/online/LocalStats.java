package com.desertkun.brainout.online;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistics and ranking of everyone playing on a private server, shown by the game's stats menu.
 *
 * Mounted on the server's HTTP port under /private/stats, needs a session token (access_token):
 *   GET /private/stats   {"me": name, "players": [{name, online, stats..., week: {...}}],
 *                         "challenges": [...], "weekEnds": millis, "lastWeek": [{challenge, name, value}]}
 *
 * The games of a server (lobby, open world, deathmatch...) run in separate processes sharing the
 * data directory: each one writes who plays on it to online-<mode>.json every few seconds, so any
 * of them can tell where everyone is.
 *
 * Weekly challenges compare what everyone did since Monday: the stats of each account are noted
 * the first time it is seen in a week (weekly-<week>.json), the progress is the difference.
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

    // weekly challenges: name shown, value in the "week" object of each player
    public static final String[][] CHALLENGES = {
        {"Le plus de kills", "kills"},
        {"Le plus de tirs a la tete", "headshots"},
        {"Le plus de sorties du monde ouvert", "fpExits"},
        {"Le plus de kills en monde ouvert", "fpKills"},
        {"Le plus de temps de jeu (minutes)", "minutes"},
    };

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private static final Set<String> online = ConcurrentHashMap.newKeySet();
    private static Timer timer;

    public static void PlayerJoined(String account)
    {
        online.add(account);
        WriteOnline();

        // the week's progress counts from here if this is the player's first game this week
        JSONObject profile = AccountStore.Get().loadProfile(account);
        if (profile != null)
        {
            Baseline(WeekKey(LocalDate.now(ZONE)), account, Stats(profile), true);
        }
    }

    private static String WeekKey(LocalDate date)
    {
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-" + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Next Monday at midnight, when the challenges start over. */
    private static long WeekEnds()
    {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return monday.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** The stats an account had when first seen in the given week, noted now if missing and create is set. */
    private static synchronized JSONObject Baseline(String week, String account, JSONObject current, boolean create)
    {
        AccountStore accounts = AccountStore.Get();
        String file = "weekly-" + week + ".json";
        JSONObject all = accounts.loadShared(file);
        if (all == null)
            all = new JSONObject();

        String key = account.toLowerCase();
        JSONObject baseline = all.optJSONObject(key);

        if (baseline == null && create && current != null)
        {
            baseline = current;
            all.put(key, baseline);
            accounts.saveShared(file, all);
        }

        return baseline;
    }

    private static JSONObject Stats(JSONObject profile)
    {
        JSONObject stats = profile.optJSONObject("stats");
        if (stats == null)
            stats = new JSONObject();

        JSONObject result = new JSONObject();

        for (String[] stat : STATS)
        {
            result.put(stat[1], (long) stats.optDouble(stat[0], 0));
        }

        return result;
    }

    private static JSONObject Difference(JSONObject now, JSONObject before)
    {
        JSONObject result = new JSONObject();

        for (String[] challenge : CHALLENGES)
        {
            long value = now.optLong(challenge[1]) - (before != null ? before.optLong(challenge[1]) : now.optLong(challenge[1]));
            result.put(challenge[1], Math.max(0, value));
        }

        return result;
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

            respond(exchange, 200, Snapshot().put("me", accounts.getDisplayName(me)));
        }
        catch (Exception e)
        {
            respond(exchange, 500, new JSONObject().put("error", String.valueOf(e.getMessage())));
        }
    }

    /** Everyone's stats, where they play and their progress in the week's challenges. */
    public static JSONObject Snapshot()
    {
        AccountStore accounts = AccountStore.Get();

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

        LocalDate today = LocalDate.now(ZONE);
        String week = WeekKey(today);
        String lastWeek = WeekKey(today.minusWeeks(1));

        JSONArray players = new JSONArray();
        // last week's progress: from last week's baseline to this week's one
        JSONObject lastWeekProgress = new JSONObject();

        for (Object[] entry : accounts.listProfiles())
        {
            String name = (String) entry[0];
            JSONObject profile = (JSONObject) entry[1];
            JSONObject stats = Stats(profile);

            JSONObject player = new JSONObject(stats.toString());
            player.put("name", name);
            player.put("online", where.optString(name.toLowerCase(), ""));

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

            JSONObject baseline = Baseline(week, name, stats, true);
            player.put("week", Difference(stats, baseline));

            JSONObject before = Baseline(lastWeek, name, null, false);
            if (before != null && baseline != null)
            {
                lastWeekProgress.put(name, Difference(baseline, before));
            }

            players.put(player);
        }

        JSONArray challenges = new JSONArray();
        JSONArray winners = new JSONArray();

        for (String[] challenge : CHALLENGES)
        {
            challenges.put(new JSONObject().put("title", challenge[0]).put("stat", challenge[1]));

            String best = null;
            long bestValue = 0;

            for (String name : lastWeekProgress.keySet())
            {
                long value = lastWeekProgress.getJSONObject(name).optLong(challenge[1]);
                if (value > bestValue)
                {
                    best = name;
                    bestValue = value;
                }
            }

            if (best != null)
            {
                winners.put(new JSONObject().put("challenge", challenge[0]).put("name", best).put("value", bestValue));
            }
        }

        return new JSONObject()
            .put("players", players)
            .put("challenges", challenges)
            .put("weekEnds", WeekEnds())
            .put("lastWeek", winners);
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
