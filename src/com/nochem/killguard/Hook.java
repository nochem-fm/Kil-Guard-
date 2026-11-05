package com.nochem.killguard;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.Binder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runs inside system_server. Stops "clear all" / one-tap cleaners from killing the apps the user picked
 * (for example a third-party launcher), while crashes, ANRs, updates, uninstalls, low-memory kills and a
 * force-stop the user does from Settings or adb still work.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "KillGuard: ";
    static final String PKG = "com.nochem.killguard";
    static final File ROOT_CONF = new File("/data/system/killguard.conf");

    private static XSharedPreferences prefs;
    private static final ThreadLocal<Boolean> IN_REMOVE = new ThreadLocal<Boolean>();
    private static volatile Set<String> guarded = Collections.emptySet();
    private static volatile boolean aggressive;
    private static long lastLoad;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (PKG.equals(lp.packageName)) {   // lets the settings screen show that the module is active
            Class<?> ui = XposedHelpers.findClassIfExists(PKG + ".MainActivity", lp.classLoader);
            if (ui != null) XposedBridge.hookAllMethods(ui, "moduleActive", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(Boolean.TRUE); }
            });
            return;
        }
        if (!"android".equals(lp.packageName)) return;
        ClassLoader cl = lp.classLoader;
        XposedBridge.log(TAG + "loaded in system_server");

        // 1a. most kills (force-stop, kill background, cleaners) go through removeProcessLocked(app, ..., reason):
        //     skipping it whole keeps the process AND its bookkeeping in AMS consistent
        XC_MethodHook remove = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                IN_REMOVE.set(Boolean.TRUE);
                if (p.args.length == 0) return;
                String pkg = pkgOf(p.args[0]);
                if (pkg == null || !isGuarded(pkg)) return;
                String reason = "";
                for (Object a : p.args) if (a instanceof String) reason = (String) a;
                if (shouldBlock(reason)) {
                    XposedBridge.log(TAG + "blocked kill of " + pkg + " (" + reason + ")");
                    p.setResult(Boolean.FALSE);
                } else {
                    XposedBridge.log(TAG + "allowed kill of " + pkg + " (" + reason + ")");
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { IN_REMOVE.set(Boolean.FALSE); }
        };
        hook(cl, "com.android.server.am.ProcessList", "removeProcessLocked", remove);
        hook(cl, "com.android.server.am.ActivityManagerService", "removeProcessLocked", remove);

        // 1b. direct kills ("remove task" from recents, vendor cleaners) call ProcessRecord.killLocked(reason, ...)
        hook(cl, "com.android.server.am.ProcessRecord", "killLocked", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(IN_REMOVE.get())) return;   // already decided in removeProcessLocked
                String pkg = pkgOf(p.thisObject);
                if (pkg == null || !isGuarded(pkg)) return;
                String reason = p.args.length > 0 && p.args[0] instanceof String ? (String) p.args[0] : "";
                if (shouldBlock(reason)) {
                    XposedBridge.log(TAG + "blocked kill of " + pkg + " (" + reason + ")");
                    p.setResult(null);
                } else {
                    XposedBridge.log(TAG + "allowed kill of " + pkg + " (" + reason + ")");
                }
            }
        });

        // 2. force-stop / kill-background requested by another app (the recents screen, a cleaner)
        XC_MethodHook byCaller = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || !(p.args[0] instanceof String)) return;
                String pkg = (String) p.args[0];
                if (!isGuarded(pkg)) return;
                int uid = Binder.getCallingUid();
                String caller = procName(Binder.getCallingPid());
                if (uid == 0 || uid == 2000 || userAllowed(caller)) {
                    XposedBridge.log(TAG + "allowed " + p.method.getName() + " " + pkg + " by " + caller);
                    return;
                }
                XposedBridge.log(TAG + "blocked " + p.method.getName() + " " + pkg + " by " + caller + " (uid " + uid + ")");
                p.setResult(null);
            }
        };
        hook(cl, "com.android.server.am.ActivityManagerService", "forceStopPackage", byCaller);
        hook(cl, "com.android.server.am.ActivityManagerService", "killBackgroundProcesses", byCaller);

        // 3. removing a task from recents also kills its process: keep the process
        XC_MethodHook task = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length < 2 || !(p.args[1] instanceof Boolean) || !((Boolean) p.args[1])) return;
                String pkg = taskPkg(p.args[0]);
                if (pkg != null && isGuarded(pkg)) {
                    p.args[1] = Boolean.FALSE;
                    XposedBridge.log(TAG + "kept process of " + pkg + " when its task was removed");
                }
            }
        };
        hook(cl, "com.android.server.wm.ActivityTaskSupervisor", "removeTask", task);      // Android 12+
        hook(cl, "com.android.server.wm.ActivityStackSupervisor", "removeTask", task);     // Android 10-11
    }

    private static void hook(ClassLoader cl, String cls, String method, XC_MethodHook h) {
        try {
            Class<?> c = XposedHelpers.findClassIfExists(cls, cl);
            if (c == null) return;
            Set<?> s = XposedBridge.hookAllMethods(c, method, h);
            XposedBridge.log(TAG + "hooked " + cls.substring(cls.lastIndexOf('.') + 1) + "." + method + " x" + (s == null ? 0 : s.size()));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not hook " + cls + "." + method + ": " + t);
        }
    }

    // ------------------------------------------------------------------ decisions

    /** Kills the user did not ask for. Crashes, ANRs, installs, updates, permission changes are never blocked. */
    static boolean shouldBlock(String reason) {
        String r = reason.toLowerCase(Locale.US);
        String[] never = {"crash", "anr", "install", "replace", "remove pkg", "pkg removed", "pkg changed", "uninstall", "update", "deleted",
            "dex", "permission", "isolated", "depends on provider", "user request", "watchdog", "native crash", "suspend", "disable", "low mem", "lowmem", "lmk", "oom", "too many"};
        for (String n : never) if (r.contains(n)) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("from pid (\\d+)").matcher(r);
        if (m.find()) {   // a force-stop: allowed when the user did it (Settings, adb/root shell), blocked from other apps
            String caller = procName(Integer.parseInt(m.group(1)));
            return !(userAllowed(caller) || !caller.contains("."));
        }
        String[] user = {"remove task", "kill background", "kill all background", "from pid", "clear", "recent", "one key", "onekey",
            "cleanup", "boost", "duraspeed", "killprocess", "kill_app", "user kill"};
        for (String u : user) if (r.contains(u)) return true;
        return aggressive;
    }

    /** A force-stop from Settings (App info > Force stop) is the user's own choice. */
    static boolean userAllowed(String caller) {
        return caller.startsWith("com.android.settings") || caller.startsWith(PKG);
    }

    static boolean isGuarded(String pkg) {
        load();
        return guarded.contains(pkg);
    }

    private static synchronized void load() {
        long now = System.currentTimeMillis();
        if (now - lastLoad < 2000) return;
        lastLoad = now;
        Set<String> s = new HashSet<String>();
        boolean aggr = false;
        try {
            if (prefs == null) prefs = new XSharedPreferences(PKG, "guard");
            if (prefs.hasFileChanged()) prefs.reload();
            Set<String> p = prefs.getStringSet("pkgs", null);
            if (p != null) s.addAll(p);
            aggr = prefs.getBoolean("aggressive", false);
        } catch (Throwable ignored) { }
        try {   // the copy the settings screen writes with root (used when the shared preferences are not readable)
            if (ROOT_CONF.canRead()) {
                BufferedReader br = new BufferedReader(new FileReader(ROOT_CONF));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("aggressive=1")) aggr = true;
                    else if (!line.isEmpty() && !line.startsWith("#") && !line.contains("=")) s.add(line);
                }
                br.close();
            }
        } catch (Throwable ignored) { }
        guarded = s;
        aggressive = aggr;
    }

    static String pkgOf(Object processRecord) {
        try {
            Object ai = XposedHelpers.getObjectField(processRecord, "info");
            if (ai instanceof ApplicationInfo) return ((ApplicationInfo) ai).packageName;
        } catch (Throwable ignored) { }
        try {
            String n = (String) XposedHelpers.getObjectField(processRecord, "processName");
            return n == null ? null : n.split(":")[0];
        } catch (Throwable ignored) { }
        return null;
    }

    static String taskPkg(Object task) {
        if (task == null) return null;
        for (String f : new String[]{"realActivity", "origActivity"}) {
            try {
                Object c = XposedHelpers.getObjectField(task, f);
                if (c instanceof ComponentName) return ((ComponentName) c).getPackageName();
            } catch (Throwable ignored) { }
        }
        try {
            Object i = XposedHelpers.getObjectField(task, "intent");
            if (i instanceof android.content.Intent && ((android.content.Intent) i).getComponent() != null)
                return ((android.content.Intent) i).getComponent().getPackageName();
        } catch (Throwable ignored) { }
        return null;
    }

    static String procName(int pid) {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/" + pid + "/cmdline"));
            String s = br.readLine();
            br.close();
            if (s == null) return "pid " + pid;
            int z = s.indexOf('\0');
            return z >= 0 ? s.substring(0, z) : s;
        } catch (Throwable t) {
            return "pid " + pid;
        }
    }
}
