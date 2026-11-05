package de.robv.android.xposed.callbacks;
public abstract class XCallback implements Comparable<XCallback> {
    public final int priority = 50;
    public static abstract class Param { }
    public int compareTo(XCallback o) { return 0; }
}
