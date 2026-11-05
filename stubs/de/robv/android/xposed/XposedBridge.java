package de.robv.android.xposed;
public final class XposedBridge {
    public synchronized static void log(String text) { }
    public synchronized static void log(Throwable t) { }
    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
}
