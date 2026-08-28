package com.lemonlightmc.minecicd;

public abstract class Config {
    public static String getString(final String path) {
        return MineCICD.instance().config.getString(path);
    }

    public static int getInt(final String path) {
        return MineCICD.instance().config.getInt(path);
    }

    public static boolean getBoolean(final String path) {
        return MineCICD.instance().config.getBoolean(path);
    }

    public static void set(final String path, final Object value) {
        MineCICD.instance().config.set(path, value);
        save();
    }

    public static void save() {
        MineCICD.instance().saveConfig();
        reload();
    }

    public static void reload() {
        MineCICD.instance().reloadConfig();
        MineCICD.instance().config = MineCICD.instance().getConfig();
    }
}
