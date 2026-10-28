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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Scanner;

/**
 * Updates the game from the releases of its GitHub repository: each build of the game is published
 * as release "client-<build>", and the game knows its own build from build.txt in its jar.
 *
 * Windows: downloads BrainOut-Setup.exe and runs it silently, it restarts the game when done.
 * macOS: downloads the app, and a script replaces BrainOut.app with it once the game has quit.
 * Linux and development builds only check nothing.
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
        String assetName = AssetName();

        if (checked || build <= 0 || assetName == null)
        {
            next.run();
            return;
        }

        checked = true;

        new Thread(() ->
        {
            String url = null;
            int latest = 0;

            try
            {
                JSONObject release = new JSONObject(Get(RELEASES));
                String tag = release.optString("tag_name", "");

                if (tag.startsWith(TAG_PREFIX))
                {
                    latest = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
                }

                JSONArray assets = release.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++)
                {
                    JSONObject asset = assets.getJSONObject(i);
                    if (assetName.equals(asset.optString("name")))
                    {
                        url = asset.optString("browser_download_url");
                    }
                }
            }
            catch (Exception e)
            {
                if (Log.INFO) Log.info("No update information: " + e.getMessage());
            }

            final String download = url;
            final int version = latest;

            Gdx.app.postRunnable(() ->
            {
                if (download == null || version <= build)
                {
                    next.run();
                    return;
                }

                Offer(build, version, download, next);
            });
        }, "update-check").start();
    }

    private static void Offer(int build, int version, String download, Runnable next)
    {
        BrainOutClient.getInstance().topState().pushMenu(new ConfirmationPopup(
            "Une mise a jour du jeu est disponible (version " + version + ", tu as la " + build + ").\n" +
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
                Gdx.app.postRunnable(() -> Install(download, next));
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

    private static void Install(String download, Runnable next)
    {
        ProgressPopup progress = new ProgressPopup();
        BrainOutClient.getInstance().topState().pushMenu(progress);

        new Thread(() ->
        {
            try
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
