package de.robv.android.xposed;
import java.util.Set;
public final class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) { }
    public java.io.File getFile() { return null; }
    public synchronized void reload() { }
    public synchronized boolean hasFileChanged() { return false; }
    public Set<String> getStringSet(String key, Set<String> defValues) { return null; }
    public boolean getBoolean(String key, boolean defValue) { return false; }
}
