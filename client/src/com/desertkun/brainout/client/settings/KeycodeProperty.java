package com.desertkun.brainout.client.settings;

import com.desertkun.brainout.L;

public class KeycodeProperty extends IntegerProperty implements Property.KeyProperty
{
    private final KeyProperties properties;
    private KeyProperties.Keys key;

    public KeycodeProperty(String name, String localization, Integer def, KeyProperties.Keys key)
    {
        super(name, localization, def);

        setKey(key);

        this.properties = null;
        this.def = def;
    }

    private final int def;

    public KeycodeProperty(String name, String localization, Integer def, KeyProperties properties, KeyProperties.Keys key)
    {
        super(name, localization, def, properties);

        setKey(key);

        this.properties = properties;
        this.def = def;
    }

    public int getDefault()
    {
        return def;
    }

    public KeyProperties.Keys getKey()
    {
        return key;
    }

    public void setKey(KeyProperties.Keys key)
    {
        this.key = key;
    }

    @Override
    public void setKeyValue(int keycode)
    {
        int previous = getValue();

        if (properties != null && keycode != previous)
        {
            // a key does one thing: the action that had it gets this one's old key
            for (Property property : properties.getProperties())
            {
                if (property != this && property instanceof KeycodeProperty &&
                    ((KeycodeProperty) property).getValue() == keycode)
                {
                    ((KeycodeProperty) property).setValue(previous);
                }
            }
        }

        setValue(keycode);

        if (properties != null)
        {
            properties.update();
        }
    }
}
