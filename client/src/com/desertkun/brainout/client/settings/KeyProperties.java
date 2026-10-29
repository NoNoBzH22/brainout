package com.desertkun.brainout.client.settings;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.ObjectMap;
import com.desertkun.brainout.L;

public class KeyProperties extends Properties
{
    public enum Keys
    {
        up,
        down,
        left,
        right,
        sit,
        run,
        reload,

        playerList,
        dropWeapon,
        dropAmmo,
        changeTeam,
        shootMode,
        unloadWeapon,
        zoom,
        chat,
        teamChat,
        console,
        voiceChat,
        hideInterface,

        slotPrimary,
        slotSecondary,
        slotSpecial,
        slotKnife,
        slotBinoculars,
        slotFlashlight,
        previousSlot,

        activate,
        squat,
        freePlayFriends,
        worldMap
    }

    private ObjectMap<Integer, Keys> keys;
    private ObjectMap<Keys, Integer> codes;

    public KeyProperties(String name, String localization)
    {
        super(name, localization);

        keys = new ObjectMap<>();
        codes = new ObjectMap<>();
    }

    public KeyProperties(String name, String localization, Properties parent)
    {
        super(name, localization, parent);

        keys = new ObjectMap<>();
        codes = new ObjectMap<>();
    }

    public Keys getKey(int keyCode)
    {
        return keys.get(keyCode);
    }

    public int getKeyCode(Keys keys, int def)
    {
        return this.codes.get(keys, def);
    }

    public void update()
    {
        repairConflicts();

        keys.clear();
        codes.clear();

        for (Property property : getProperties())
        {
            if (!(property instanceof KeycodeProperty))
                continue;

            KeycodeProperty keycodeProperty = (KeycodeProperty) property;

            keys.put(keycodeProperty.getValue(), keycodeProperty.getKey());
            codes.put(keycodeProperty.getKey(), keycodeProperty.getValue());
        }
    }

    /**
     * Settings saved by older versions can give one key to two actions, so that only one of them
     * works: the action the player chose the key for keeps it, the other one takes the default
     * key of the first, or no key if that one is taken too.
     */
    private void repairConflicts()
    {
        ObjectMap<Integer, KeycodeProperty> used = new ObjectMap<>();

        for (Property property : getProperties())
        {
            if (!(property instanceof KeycodeProperty))
                continue;

            KeycodeProperty current = (KeycodeProperty) property;

            // Escape opens the game menu: an action bound to it by mistake gets its default key back
            if (current.getValue() == Input.Keys.ESCAPE)
            {
                current.setValue(current.getDefault());
            }

            KeycodeProperty other = used.get(current.getValue());

            if (other == null || current.getValue() == Input.Keys.UNKNOWN)
            {
                used.put(current.getValue(), current);
                continue;
            }

            boolean currentChosen = current.getValue() != current.getDefault();
            KeycodeProperty winner = currentChosen ? current : other;
            KeycodeProperty loser = winner == current ? other : current;

            int replacement = used.containsKey(winner.getDefault()) || winner.getDefault() == winner.getValue() ?
                Input.Keys.UNKNOWN : winner.getDefault();

            used.put(winner.getValue(), winner);
            loser.setValue(replacement);

            if (replacement != Input.Keys.UNKNOWN)
            {
                used.put(replacement, loser);
            }
        }
    }
}
