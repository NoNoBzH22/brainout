package com.desertkun.brainout.online;

import com.esotericsoftware.minlog.Log;
import org.json.JSONObject;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Accounts of a private (offline) server: one folder per account under the data directory,
 * holding the password hash and the player's profile (inventory, stash, stats, achievements).
 *
 * Configured with environment variables:
 *   DATA_DIR      where accounts are stored                      (default: accounts)
 *   REGISTRATION  "open" lets new players create an account by logging in for the first time,
 *                 "closed" only lets existing accounts in         (default: open)
 *   ADMINS        comma separated account names given admin rights (default: none)
 *   ALLOWED_ACCOUNTS  comma separated account names allowed to register; when set,
 *                 nobody else can create an account, except ADMINS (default: anyone)
 */
public class AccountStore
{
    public enum Result
    {
        loggedIn,
        registered,
        badName,
        badCredentials,
        registrationClosed,
        tooManyAttempts
    }

    // the client sends "bo-account:<name>:<base64 sha256 of the password>" as its access token
    public static final String TOKEN_PREFIX = "bo-account:";

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_\\-]{3,20}$");
    private static final int ITERATIONS = 120000;
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_TIME = 60000;

    private static AccountStore instance;

    private final File root;
    private final boolean registrationOpen;
    private final Set<String> admins = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    // session token -> account name, for the HTTP services (see LocalMarket)
    private final Map<String, String> sessions = new HashMap<>();

    public static synchronized AccountStore Get()
    {
        if (instance == null)
        {
            instance = new AccountStore();
        }

        return instance;
    }

    private AccountStore()
    {
        String dir = System.getenv("DATA_DIR");
        root = new File(dir != null && !dir.isEmpty() ? dir : "accounts");
        if (!root.isDirectory() && !root.mkdirs())
        {
            if (Log.ERROR) Log.error("Cannot create the accounts directory " + root.getAbsolutePath());
        }

        String registration = System.getenv("REGISTRATION");
        registrationOpen = registration == null || !registration.trim().equalsIgnoreCase("closed");

        readList(System.getenv("ADMINS"), admins);
        readList(System.getenv("ALLOWED_ACCOUNTS"), allowed);

        if (Log.INFO) Log.info("Accounts: " + root.getAbsolutePath() +
            ", registration " + (registrationOpen ? "open" : "closed") + ", admins " + admins +
            (allowed.isEmpty() ? "" : ", allowed " + allowed));
    }

    private static void readList(String value, Set<String> into)
    {
        if (value == null)
            return;

        for (String name : value.split(","))
        {
            if (!name.trim().isEmpty())
            {
                into.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
    }

    public static boolean IsAccountToken(String token)
    {
        return token != null && token.startsWith(TOKEN_PREFIX);
    }

    /** @return {name, password hash} or null if the token is malformed */
    public static String[] ParseToken(String token)
    {
        if (!IsAccountToken(token))
            return null;

        String[] parts = token.substring(TOKEN_PREFIX.length()).split(":", 2);
        if (parts.length != 2 || parts[1].isEmpty())
            return null;

        return parts;
    }

    public synchronized Result authenticate(String name, String clientHash, String address)
    {
        if (name == null || !NAME.matcher(name).matches())
            return Result.badName;

        String key = key(name);
        String lockKey = key + "@" + address;

        Long locked = lockedUntil.get(lockKey);
        if (locked != null && locked > System.currentTimeMillis())
            return Result.tooManyAttempts;

        File account = new File(accountDir(key), "account.json");

        if (!account.isFile())
        {
            // admins can always create their account
            if (!admins.contains(key) && (!registrationOpen || (!allowed.isEmpty() && !allowed.contains(key))))
                return Result.registrationClosed;

            byte[] salt = new byte[16];
            random.nextBytes(salt);

            JSONObject data = new JSONObject();
            data.put("name", name);
            data.put("salt", Base64.getEncoder().encodeToString(salt));
            data.put("hash", hash(clientHash, salt));
            data.put("created", System.currentTimeMillis());

            write(account, data.toString(4));

            if (Log.INFO) Log.info("Registered account " + name);
            return Result.registered;
        }

        JSONObject data = new JSONObject(read(account));
        byte[] salt = Base64.getDecoder().decode(data.getString("salt"));
        String expected = data.getString("hash");

        if (!MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            hash(clientHash, salt).getBytes(StandardCharsets.UTF_8)))
        {
            int failed = failures.getOrDefault(lockKey, 0) + 1;
            failures.put(lockKey, failed);

            if (failed >= MAX_FAILURES)
            {
                failures.remove(lockKey);
                lockedUntil.put(lockKey, System.currentTimeMillis() + LOCK_TIME);
                if (Log.WARN) Log.warn("Too many failed logins for " + name + " from " + address);
            }

            return Result.badCredentials;
        }

        failures.remove(lockKey);
        lockedUntil.remove(lockKey);
        return Result.loggedIn;
    }

    /** The registered spelling of the account name. */
    public synchronized String getDisplayName(String name)
    {
        File account = new File(accountDir(key(name)), "account.json");

        if (account.isFile())
        {
            return new JSONObject(read(account)).optString("name", name);
        }

        return name;
    }

    public boolean isAdmin(String name)
    {
        return admins.contains(key(name));
    }

    public synchronized JSONObject loadProfile(String name)
    {
        File profile = new File(accountDir(key(name)), "profile.json");

        if (!profile.isFile())
            return null;

        try
        {
            return new JSONObject(read(profile));
        }
        catch (Exception e)
        {
            if (Log.ERROR) Log.error("Corrupted profile for " + name + ": " + e.getMessage());
            return null;
        }
    }

    /** A fresh random token identifying the account on the HTTP services until the server restarts. */
    public synchronized String newSession(String name)
    {
        sessions.values().removeIf(account -> account.equalsIgnoreCase(name));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, name);
        return token;
    }

    public synchronized String getSessionAccount(String token)
    {
        return token != null ? sessions.get(token) : null;
    }

    /** Extra per-account JSON documents, like the market storage. */
    public synchronized JSONObject loadData(String name, String file)
    {
        File data = new File(accountDir(key(name)), file);

        if (!data.isFile())
            return null;

        try
        {
            return new JSONObject(read(data));
        }
        catch (Exception e)
        {
            if (Log.ERROR) Log.error("Corrupted " + file + " for " + name + ": " + e.getMessage());
            return null;
        }
    }

    public synchronized void saveData(String name, String file, JSONObject data)
    {
        write(new File(accountDir(key(name)), file), data.toString(2));
    }

    /** Server wide JSON documents (not tied to an account), like the market orders. */
    public synchronized JSONObject loadShared(String file)
    {
        File data = new File(root, file);

        if (!data.isFile())
            return null;

        try
        {
            return new JSONObject(read(data));
        }
        catch (Exception e)
        {
            if (Log.ERROR) Log.error("Corrupted " + file + ": " + e.getMessage());
            return null;
        }
    }

    public synchronized void saveShared(String file, JSONObject data)
    {
        write(new File(root, file), data.toString(2));
    }

    /** Every account with a saved profile: {name, profile}. */
    public synchronized List<Object[]> listProfiles()
    {
        List<Object[]> result = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);

        if (dirs == null)
            return result;

        for (File dir : dirs)
        {
            File account = new File(dir, "account.json");
            File profile = new File(dir, "profile.json");

            if (!account.isFile() || !profile.isFile())
                continue;

            try
            {
                String name = new JSONObject(read(account)).optString("name", dir.getName());
                result.add(new Object[]{name, new JSONObject(read(profile))});
            }
            catch (Exception e)
            {
                if (Log.ERROR) Log.error("Skipping account " + dir.getName() + " in the stats: " + e.getMessage());
            }
        }

        return result;
    }

    public synchronized void saveProfile(String name, JSONObject profile)
    {
        write(new File(accountDir(key(name)), "profile.json"), profile.toString(2));
    }

    private static String key(String name)
    {
        return name.toLowerCase(Locale.ROOT);
    }

    private File accountDir(String key)
    {
        File dir = new File(root, key);

        if (!dir.isDirectory() && !dir.mkdirs())
        {
            if (Log.ERROR) Log.error("Cannot create " + dir.getAbsolutePath());
        }

        return dir;
    }

    private static String hash(String clientHash, byte[] salt)
    {
        try
        {
            PBEKeySpec spec = new PBEKeySpec(clientHash.toCharArray(), salt, ITERATIONS, 256);
            byte[] derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(derived);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static String read(File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    // write to a temporary file first, so a crash never leaves a half written profile
    private static void write(File file, String contents)
    {
        try
        {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), contents.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            if (Log.ERROR) Log.error("Failed to write " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}
