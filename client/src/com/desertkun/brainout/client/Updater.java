package com.desertkun.brainout.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.ArrayMap;
import com.desertkun.brainout.BrainOutClient;
import com.desertkun.brainout.menu.Popup;
import com.desertkun.brainout.menu.popups.AlertPopup;
import com.desertkun.brainout.menu.popups.ConfirmationPopup;
import com.esotericsoftware.minlog.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Updates the game from the releases of its GitHub repository: each build of the game is published
 * as release "client-<build>", and the game knows its own build from build.txt in its jar.
 *
 * Usually only the files that changed are downloaded: the release has a manifest.json with the
 * hash of the jar and of each data package, compared with the ones next to the running jar. They
 * are put in place by a small script once the game has quit, which then starts it again.
 *
 * Without a manifest, the whole game is downloaded: Windows runs BrainOut-Setup.exe silently,
 * macOS replaces BrainOut.app. Development builds (no build.txt) check nothing.
 */
public class Updater
{
    private static final String RELEASES = "https://api.github.com/repos/NoNoBzH22/brainout/releases/latest";
    private static final String TAG_PREFIX = "client-";

    private static boolean checked = false;

    private static int Build()
    {
        try (InputStream in = Updater.class.getClassLoader().getResourceAsStream("build.txt"))
        {
            if (in == null)
                return 0;

            return Integer.parseInt(new Scanner(in, "UTF-8").next().trim());
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private static boolean IsWindows()
    {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** The folder of the running jar, with packages/ next to it. */
    private static File GameDir()
    {
        try
        {
            File jar = new File(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParentFile() : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String AssetName()
    {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win"))
            return "BrainOut-Setup.exe";

        if (os.contains("mac") && System.getProperty("brainout.app") != null)
            return "BrainOut-macOS-arm64.zip";

        return null;
    }

    /**
     * Once per game: looks for a newer build and offers to install it, then runs next
     * (right away when there is nothing to do or the player declines).
     */
    public static void Check(Runnable next)
    {
        int build = Build();
        File gameDir = GameDir();

        if (checked || build <= 0 || gameDir == null)
        {
            next.run();
            return;
        }

        checked = true;

        new Thread(() ->
        {
            Plan plan = null;

            try
            {
                plan = Plan(build, gameDir);
            }
            catch (Exception e)
            {
                if (Log.INFO) Log.info("No update information: " + e.getMessage());
            }

            final Plan update = plan;

            Gdx.app.postRunnable(() ->
            {
                if (update == null)
                {
                    next.run();
                    return;
                }

                Offer(build, update, next);
            });
        }, "update-check").start();
    }

    /** What to download to go from this build to the latest one, null when up to date. */
    private static class Plan
    {
        int version;
        File gameDir;
        // files that changed: path in the game folder -> download url
        ArrayMap<String, String> files = new ArrayMap<>();
        long size;
        // the whole game, when the release has no manifest
        String full;
    }

    private static Plan Plan(int build, File gameDir) throws Exception
    {
        JSONObject release = new JSONObject(Get(RELEASES));
        String tag = release.optString("tag_name", "");

        if (!tag.startsWith(TAG_PREFIX))
            return null;

        Plan plan = new Plan();
        plan.version = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
        plan.gameDir = gameDir;

        if (plan.version <= build)
            return null;

        Map<String, String> urls = new HashMap<>();
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++)
        {
            JSONObject asset = assets.getJSONObject(i);
            urls.put(asset.optString("name"), asset.optString("browser_download_url"));
        }

        String manifestUrl = urls.get("manifest.json");

        if (manifestUrl == null)
        {
            String name = AssetName();
            plan.full = name != null ? urls.get(name) : null;
            return plan.full != null ? plan : null;
        }

        JSONObject files = new JSONObject(GetFollowing(manifestUrl)).getJSONObject("files");

        for (String path : files.keySet())
        {
            JSONObject entry = files.getJSONObject(path);
            File local = new File(gameDir, path);

            if (local.isFile() && entry.optString("sha256").equalsIgnoreCase(Sha256(local)))
                continue;

            String url = urls.get(entry.optString("asset"));
            if (url == null)
                throw new Exception("missing " + entry.optString("asset"));

            plan.files.put(path, url);
            plan.size += entry.optLong("size");
        }

        // a newer build with the same files: nothing to download
        return plan.files.size > 0 ? plan : null;
    }

    private static String Sha256(File file) throws Exception
    {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = new FileInputStream(file))
        {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                digest.update(buffer, 0, read);
            }
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest())
        {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void Offer(int build, Plan plan, Runnable next)
    {
        String size = plan.full != null ? "" :
            " (" + Math.max(1, plan.size / (1024 * 1024)) + " Mo a telecharger)";

        BrainOutClient.getInstance().topState().pushMenu(new ConfirmationPopup(
            "Une mise a jour du jeu est disponible : version " + plan.version + ", tu as la " + build + size + ".\n" +
            "L'installer maintenant ? Le jeu redemarrera tout seul.")
        {
            @Override
            public String buttonYes()
            {
                return "Mettre a jour";
            }

            @Override
            public String buttonNo()
            {
                return "Plus tard";
            }

            @Override
            public void yes()
            {
                Gdx.app.postRunnable(() -> Install(plan, next));
            }

            @Override
            public void no()
            {
                Gdx.app.postRunnable(next);
            }
        });
    }

    private static class ProgressPopup extends Popup
    {
        private Label label;

        ProgressPopup()
        {
            super("Telechargement de la mise a jour...");
            setButtons(new ArrayMap<>());
        }

        @Override
        protected void initContent(Table data)
        {
            label = new Label(text, BrainOutClient.Skin, "title-medium");
            label.setAlignment(Align.center);
            label.setWrap(true);
            data.add(label).pad(16).center().expand().fill().row();
        }

        void setText(String text)
        {
            if (label != null)
            {
                label.setText(text);
            }
        }
    }

    private static void Install(Plan plan, Runnable next)
    {
        ProgressPopup progress = new ProgressPopup();
        BrainOutClient.getInstance().topState().pushMenu(progress);

        new Thread(() ->
        {
            try
            {
                if (plan.full != null)
                {
                    InstallFull(plan.full, progress);
                }
                else
                {
                    InstallFiles(plan, progress);
                }

                Gdx.app.postRunnable(() -> Gdx.app.exit());
            }
            catch (Exception e)
            {
                if (Log.ERROR) Log.error("Update failed: " + e);

                Gdx.app.postRunnable(() ->
                {
                    progress.pop();
                    BrainOutClient.getInstance().topState().pushMenu(new AlertPopup(
                        "La mise a jour a echoue (" + e.getMessage() + "). Tu peux continuer a jouer.")
                    {
                        @Override
                        public void ok()
                        {
                            next.run();
                        }
                    });
                });
            }
        }, "update-download").start();
    }

    private static void InstallFull(String download, ProgressPopup progress) throws Exception
    {
        String name = download.substring(download.lastIndexOf('/') + 1);
        File file = new File(Files.createTempDirectory("brainout-update").toFile(), name);

        Download(download, file, percent -> Gdx.app.postRunnable(() ->
            progress.setText("Telechargement de la mise a jour... " + percent + " %")));

        Gdx.app.postRunnable(() -> progress.setText("Installation..."));

        if (name.endsWith(".exe"))
        {
            // Inno Setup: silent install into the same folder, the installer starts the game again
            new ProcessBuilder(file.getAbsolutePath(), "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART",
                "/CLOSEAPPLICATIONS").start();
        }
        else
        {
            ReplaceMacApp(file);
        }
    }

    /**
     * Downloads the changed files into update/ next to the game, then a script started now moves
     * them over the old ones once the game has quit (a running game keeps its jar locked on
     * Windows) and starts it again.
     */
    private static void InstallFiles(Plan plan, ProgressPopup progress) throws Exception
    {
        File staging = new File(plan.gameDir, "update");
        DeleteTree(staging);

        for (int i = 0; i < plan.files.size; i++)
        {
            String path = plan.files.getKeyAt(i);
            String url = plan.files.getValueAt(i);
            File target = new File(staging, path);
            target.getParentFile().mkdirs();

            final String step = "Telechargement (" + (i + 1) + "/" + plan.files.size + ") ";
            Download(url, target, percent -> Gdx.app.postRunnable(() ->
                progress.setText(step + percent + " %")));
        }

        Gdx.app.postRunnable(() -> progress.setText("Installation..."));

        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        File dir = plan.gameDir;

        if (IsWindows())
        {
            File script = new File(dir, "update.cmd");
            String text =
                "@echo off\r\n" +
                ":wait\r\n" +
                "tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul\r\n" +
                "if not errorlevel 1 (\r\n" +
                "  timeout /t 1 /nobreak >nul\r\n" +
                "  goto wait\r\n" +
                ")\r\n" +
                "robocopy \"%~dp0update\" \"%~dp0.\" /E /MOVE /NFL /NDL /NJH /NJS /NP >nul\r\n" +
                "rmdir /S /Q \"%~dp0update\" 2>nul\r\n" +
                "if exist \"%~dp0..\\BrainOut.exe\" start \"\" \"%~dp0..\\BrainOut.exe\"\r\n";

            Files.write(script.toPath(), text.getBytes(StandardCharsets.UTF_8));
            new ProcessBuilder("cmd", "/c", "start", "\"BrainOut\"", "/min", script.getAbsolutePath())
                .directory(dir).start();
        }
        else
        {
            String app = System.getProperty("brainout.app");
            // by its bundle id: macOS may have run the app from a temporary copy that is gone by then
            String restart = app != null ? "open -b com.nonobzh22.brainout || open \"" + app + "\"" :
                "[ -x \"$DIR/../BrainOut.sh\" ] && nohup \"$DIR/../BrainOut.sh\" > /dev/null 2>&1 &";

            File script = new File(dir, "update.sh");
            String text =
                "#!/bin/bash\n" +
                "DIR=\"" + dir.getAbsolutePath() + "\"\n" +
                "while kill -0 " + pid + " 2>/dev/null; do sleep 0.5; done\n" +
                "cp -R \"$DIR/update/.\" \"$DIR/\" && rm -rf \"$DIR/update\"\n" +
                restart + "\n";

            Files.write(script.toPath(), text.getBytes(StandardCharsets.UTF_8));
            new ProcessBuilder("/bin/bash", script.getAbsolutePath())
                .redirectErrorStream(true)
                .redirectOutput(new File(dir, "update.log"))
                .start();
        }
    }

    private static void DeleteTree(File file)
    {
        File[] children = file.listFiles();
        if (children != null)
        {
            for (File child : children)
            {
                DeleteTree(child);
            }
        }
        file.delete();
    }

    /** A script waits for the game to quit, swaps the app for the new one and starts it. */
    private static void ReplaceMacApp(File zip) throws Exception
    {
        String app = System.getProperty("brainout.app");
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

        File script = new File(zip.getParentFile(), "update.sh");
        String text =
            "#!/bin/bash\n" +
            "APP=\"$1\"; ZIP=\"$2\"; PID=\"$3\"\n" +
            "while kill -0 \"$PID\" 2>/dev/null; do sleep 0.5; done\n" +
            "NEW=\"$(dirname \"$ZIP\")/new\"\n" +
            "mkdir -p \"$NEW\" && ditto -x -k \"$ZIP\" \"$NEW\" || exit 1\n" +
            "[ -d \"$NEW/BrainOut.app\" ] || exit 1\n" +
            "rm -rf \"$APP.old\" && mv \"$APP\" \"$APP.old\" && mv \"$NEW/BrainOut.app\" \"$APP\" && rm -rf \"$APP.old\"\n" +
            "xattr -dr com.apple.quarantine \"$APP\" 2>/dev/null\n" +
            "open \"$APP\"\n";

        Files.write(script.toPath(), text.getBytes(StandardCharsets.UTF_8));

        new ProcessBuilder("/bin/bash", script.getAbsolutePath(), app, zip.getAbsolutePath(), pid)
            .redirectErrorStream(true)
            .redirectOutput(new File(zip.getParentFile(), "update.log"))
            .start();
    }

    private interface Progress
    {
        void percent(int percent);
    }

    private static HttpURLConnection Open(String url) throws Exception
    {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "BrainOut-Updater");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    private static String Get(String url) throws Exception
    {
        HttpURLConnection connection = Open(url);
        connection.setRequestProperty("Accept", "application/vnd.github+json");

        try (InputStream in = connection.getInputStream())
        {
            return new Scanner(in, "UTF-8").useDelimiter("\\A").next();
        }
        finally
        {
            connection.disconnect();
        }
    }

    private static String GetFollowing(String url) throws Exception
    {
        File temp = File.createTempFile("brainout-manifest", ".json");

        try
        {
            Download(url, temp, percent -> {});
            return new String(Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8);
        }
        finally
        {
            temp.delete();
        }
    }

    private static void Download(String url, File to, Progress progress) throws Exception
    {
        HttpURLConnection connection = Open(url);

        // GitHub sends the file from another host: follow the redirects by hand (https to https)
        for (int i = 0; i < 5; i++)
        {
            int code = connection.getResponseCode();
            if (code / 100 != 3)
                break;

            String location = connection.getHeaderField("Location");
            connection.disconnect();
            connection = Open(location);
        }

        if (connection.getResponseCode() != 200)
            throw new Exception("HTTP " + connection.getResponseCode());

        long total = connection.getContentLengthLong();
        long done = 0;
        int lastPercent = -1;

        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(to))
        {
            byte[] buffer = new byte[1 << 16];
            int read;

            while ((read = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, read);
                done += read;

                int percent = total > 0 ? (int) (done * 100 / total) : 0;
                if (percent != lastPercent)
                {
                    lastPercent = percent;
                    progress.percent(percent);
                }
            }
        }
        finally
        {
            connection.disconnect();
        }

        if (total > 0 && done != total)
            throw new Exception("telechargement incomplet");
    }
}
