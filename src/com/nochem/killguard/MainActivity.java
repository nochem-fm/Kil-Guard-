package com.nochem.killguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pick the apps that "clear all" and cleaners must not kill. Compact single-screen UI, no title bar. */
public class MainActivity extends Activity {
    static final class App { String label, pkg; boolean system; Drawable icon; }

    SharedPreferences prefs;
    final Set<String> chosen = new HashSet<String>();
    final List<App> all = new ArrayList<App>(), shown = new ArrayList<App>();
    int filterMode;            // 0 = apps, 1 = protected only, 2 = everything incl. system
    String query = "";
    BaseAdapter adapter;
    TextView statusChip, empty, countChip, aggrChip;
    final TextView[] modeChips = new TextView[3];

    int BG, SURF, TXT, DIM, ACC, ON_ACC, GOOD, BAD;

    /** Replaced with true by the module itself when Vector / LSPosed has it enabled. */
    static boolean moduleActive() { return false; }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            prefs = getSharedPreferences("guard", Context.MODE_WORLD_READABLE);   // allowed by LSPosed/Vector for enabled modules
        } catch (SecurityException e) {
            prefs = getSharedPreferences("guard", Context.MODE_PRIVATE);
        }
        Set<String> s = prefs.getStringSet("pkgs", null);
        if (s != null) chosen.addAll(s);
        if (chosen.isEmpty() && !prefs.getBoolean("init", false)) {   // first run: protect the default launcher
            String home = defaultHome();
            if (home != null) chosen.add(home);
            save();
        }
        colors();
        build();
        loadApps();
        if (!prefs.getBoolean("init", false)) { prefs.edit().putBoolean("init", true).apply(); showInfo(); }
    }

    void colors() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        BG = dark ? 0xFF111418 : 0xFFF6F7F9;
        SURF = dark ? 0xFF1E232A : 0xFFFFFFFF;
        TXT = dark ? 0xFFE8EAED : 0xFF1B1F24;
        DIM = dark ? 0xFF9AA0A6 : 0xFF6B7280;
        ACC = 0xFF3B82F6;
        ON_ACC = 0xFFFFFFFF;
        GOOD = dark ? 0xFF4ADE80 : 0xFF15803D;
        BAD = dark ? 0xFFF87171 : 0xFFB91C1C;
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (!dark) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
    }

    GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    TextView chip(String s) {
        TextView c = text(s, 13, TXT);
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        c.setGravity(Gravity.CENTER);
        return c;
    }

    void chipOn(TextView c, boolean on) {
        c.setBackground(round(on ? ACC : SURF, 16));
        c.setTextColor(on ? ON_ACC : TXT);
    }

    void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(10), dp(12), 0);
        root.setFitsSystemWindows(true);

        // header: name, status, info, log - one line
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("Kill Guard", 18, TXT);
        name.setTypeface(null, Typeface.BOLD);
        head.addView(name);
        statusChip = text("", 12, TXT);
        statusChip.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.setMarginStart(dp(8));
        head.addView(statusChip, sl);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView log = iconBtn("☰");
        log.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showLog(); } });
        TextView info = iconBtn("?");
        info.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showInfo(); } });
        head.addView(log);
        head.addView(info);
        root.addView(head);

        // search
        EditText search = new EditText(this);
        search.setHint("⌕  חיפוש אפליקציה");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(TXT);
        search.setHintTextColor(DIM);
        search.setBackground(round(SURF, 14));
        search.setPadding(dp(14), dp(9), dp(14), dp(9));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) { query = e.toString().trim().toLowerCase(Locale.ROOT); filter(); }
        });
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, -2);
        srl.topMargin = dp(8);
        root.addView(search, srl);

        // filter chips + hard mode, scroll sideways on narrow screens
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(8), 0, dp(6));
        String[] labels = {"אפליקציות", "מוגנות", "הכל כולל מערכת"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView c = chip(labels[i]);
            modeChips[i] = c;
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { filterMode = m; filter(); } });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.setMarginEnd(dp(6));
            chips.addView(c, cl);
        }
        aggrChip = chip("");
        aggrChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean on = !prefs.getBoolean("aggressive", false);
                prefs.edit().putBoolean("aggressive", on).apply();
                save();
                refresh();
                if (on) new AlertDialog.Builder(MainActivity.this).setTitle("מצב קשוח")
                    .setMessage("חוסם כל הריגה של האפליקציות המוגנות, חוץ מקריסה, עדכון, הסרה, מחסור בזיכרון ועצירה בכפייה שאתה עושה בעצמך. השתמש בו רק אם המצב הרגיל לא מספיק.")
                    .setPositiveButton("הבנתי", null).show();
            }
        });
        chips.addView(aggrChip);
        hs.addView(chips);
        root.addView(hs);

        // list
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(SURF, 16));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(4));
        adapter = new BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int i) { return shown.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup parent) { return row(shown.get(i)); }
        };
        list.setAdapter(adapter);
        empty = text("טוען אפליקציות…", 14, DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(24), 0, dp(24));
        box.addView(empty);
        box.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, 0, 1f);
        bl.bottomMargin = dp(10);
        root.addView(box, bl);

        setContentView(root);
        refresh();
    }

    TextView iconBtn(String g) {
        TextView t = text(g, 17, TXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(SURF, 18));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(36), dp(36));
        l.setMarginStart(dp(6));
        t.setLayoutParams(l);
        return t;
    }

    /** 1 = the hooks are running in system_server (this boot), 0 = they are not, -1 = cannot tell (no root). */
    volatile int sysState = -1;
    String statusText = "";

    void checkSystem() {
        new Thread(new Runnable() {
            public void run() {
                String st = su("cat /data/system/killguard.status 2>/dev/null; echo __END__");
                int s = -1;
                if (st != null && st.contains("__END__")) {
                    s = 0;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("boot=(\\d+)").matcher(st);
                    long boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
                    if (m.find() && Math.abs(Long.parseLong(m.group(1)) - boot) < 5 * 60000L && st.contains("hooked ")) s = 1;
                    statusText = st.replace("__END__", "").trim();
                }
                sysState = s;
                runOnUiThread(new Runnable() { public void run() { refresh(); } });
            }
        }).start();
    }

    /** Runs a root command and returns its output, or null without root. */
    static String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return p.waitFor() == 0 || b.length() > 0 ? b.toString() : null;
        } catch (Exception e) { return null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkSystem();
    }

    /** Status chip, mode chips and hard-mode chip. */
    void refresh() {
        boolean on = moduleActive() && sysState != 0;
        String why = !moduleActive() ? "● לא פעיל" : sysState == 0 ? "● לא פעיל במערכת" : "";
        statusChip.setText(on ? "● פעיל · " + chosen.size() + " מוגנות" : why);
        statusChip.setTextColor(on ? GOOD : BAD);
        statusChip.setBackground(round((on ? GOOD : BAD) & 0x22FFFFFF, 12));
        statusChip.setOnClickListener(on ? null : new View.OnClickListener() { public void onClick(View v) { showInfo(); } });
        for (int i = 0; i < 3; i++) chipOn(modeChips[i], filterMode == i);
        boolean aggr = prefs.getBoolean("aggressive", false);
        aggrChip.setText((aggr ? "✓ " : "") + "מצב קשוח");
        aggrChip.setBackground(round(aggr ? (BAD & 0x33FFFFFF) : SURF, 16));
        aggrChip.setTextColor(aggr ? BAD : TXT);
    }

    void showInfo() {
        String msg = "האפליקציות המוגנות לא ייסגרו כשלוחצים 'סגור הכל' באחרונות, או כשמנקה זיכרון סוגר אפליקציות.\n\n"
            + "ממשיכים לעבוד כרגיל: קריסה, עדכון, הסרה, מחסור אמיתי בזיכרון, ו'עצירה בכפייה' שאתה עושה בהגדרות.\n\n"
            + (moduleActive() && sysState == 0 ? "המודול פעיל באפליקציה אבל לא רץ במערכת: ב-Vector / LSPosed סמן בהיקף (Scope) של המודול את 'מסגרת המערכת' (System Framework / android) ואתחל את הטלפון."
            : moduleActive() ? "המודול פעיל. שינויים ברשימה נכנסים לתוקף מיד."
            : "המודול עוד לא פעיל: ב-Vector / LSPosed הפעל את 'שומר מהריגה', סמן בהיקף את 'מסגרת המערכת' (System Framework), ואתחל את הטלפון.")
            + "\n\n☰ יומן: מה נחסם ומה הותר.";
        String ver = "";
        try { ver = " " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        msg += "\n\nKill Guard" + ver + "\nנבנה על ידי @נוחעם-FM מפורום מתמחים טופ";
        new AlertDialog.Builder(this).setTitle("Kill Guard").setMessage(msg).setPositiveButton("סגור", null)
            .setNeutralButton("@נוחעם-FM", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mitmachim.top/user/%D7%A0%D7%95%D7%97%D7%A2%D7%9D-fm")));
                    } catch (Exception ignored) { }
                }
            }).show();
    }

    String defaultHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            String p = r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
            return p == null || p.equals("android") || p.equals("com.android.settings") ? null : p;   // Settings = FallbackHome, not a launcher
        } catch (Exception e) { return null; }
    }

    void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                final PackageManager pm = getPackageManager();
                final List<App> l = new ArrayList<App>();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    App a = new App();
                    a.pkg = ai.packageName;
                    a.label = String.valueOf(ai.loadLabel(pm));
                    a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && pm.getLaunchIntentForPackage(ai.packageName) == null;
                    try { a.icon = ai.loadIcon(pm); } catch (Exception ignored) { }
                    l.add(a);
                }
                runOnUiThread(new Runnable() { public void run() { all.clear(); all.addAll(l); sortAll(); filter(); } });
            }
        }).start();
    }

    void sortAll() {
        Collections.sort(all, new Comparator<App>() {
            public int compare(App x, App y) {
                boolean cx = chosen.contains(x.pkg), cy = chosen.contains(y.pkg);
                if (cx != cy) return cx ? -1 : 1;
                return x.label.compareToIgnoreCase(y.label);
            }
        });
    }

    void filter() {
        shown.clear();
        for (App a : all) {
            boolean on = chosen.contains(a.pkg);
            if (filterMode == 1 && !on) continue;
            if (filterMode == 0 && a.system && !on) continue;
            if (!query.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(query) && !a.pkg.contains(query)) continue;
            shown.add(a);
        }
        adapter.notifyDataSetChanged();
        if (!all.isEmpty()) empty.setText(filterMode == 1 ? "אין אפליקציות מוגנות. סמן אפליקציה ברשימה." : "לא נמצאו אפליקציות");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        refresh();
    }

    View row(final App a) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(7), dp(8), dp(7));
        ImageView ic = new ImageView(this);
        if (a.icon != null) ic.setImageDrawable(a.icon);
        r.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(10), 0, dp(6), 0);
        TextView n = text(a.label, 15, TXT);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView p = text(a.pkg, 11, DIM);
        p.setSingleLine(true);
        p.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        p.setTextDirection(View.TEXT_DIRECTION_LTR);
        p.setGravity(Gravity.START);
        txt.addView(n);
        txt.addView(p);
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        final CheckBox cb = new CheckBox(this);
        cb.setChecked(chosen.contains(a.pkg));
        cb.setClickable(false);
        cb.setFocusable(false);
        cb.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ACC, DIM}));
        r.addView(cb);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (chosen.contains(a.pkg)) chosen.remove(a.pkg); else chosen.add(a.pkg);
                cb.setChecked(chosen.contains(a.pkg));
                save();
                refresh();
            }
        });
        return r;
    }

    /** Saves to the module's shared preferences, and with root also to /data/system/killguard.conf (the hook reads both). */
    void save() {
        prefs.edit().putStringSet("pkgs", new HashSet<String>(chosen)).apply();
        final StringBuilder conf = new StringBuilder("# Kill Guard\n");
        if (prefs.getBoolean("aggressive", false)) conf.append("aggressive=1\n");
        for (String p : chosen) conf.append(p).append('\n');
        new Thread(new Runnable() {
            public void run() {
                try {
                    Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
                    OutputStream o = p.getOutputStream();
                    o.write(("cat > /data/system/killguard.conf <<'KGEOF'\n" + conf + "KGEOF\n"
                        + "chmod 644 /data/system/killguard.conf; chcon u:object_r:system_data_file:s0 /data/system/killguard.conf 2>/dev/null; exit\n").getBytes("UTF-8"));
                    o.close();
                    p.waitFor();
                } catch (Exception ignored) { }   // no root: the shared preferences are enough on Vector / LSPosed
            }
        }).start();
    }

    void showLog() {
        new Thread(new Runnable() {
            public void run() {
                StringBuilder pk = new StringBuilder();
                for (String c : chosen) pk.append(pk.length() > 0 ? "|" : "").append(c.replace(".", "\\."));
                String kills = pk.length() == 0 ? "" : su("logcat -b events -d 2>/dev/null | grep -E 'am_kill|am_proc_died' | grep -E '" + pk + "' | tail -n 15");
                String st = su("cat /data/system/killguard.status 2>/dev/null");
                String out;
                if (st == null && kills == null) out = "אין root לקריאת היומן. הרשומות מופיעות גם ביומן (Logs) של Vector / LSPosed.";
                else {
                    StringBuilder b = new StringBuilder();
                    String s = st == null ? "" : st.trim();
                    int sep = s.indexOf("\n--");
                    String hooks = sep < 0 ? s : s.substring(0, sep), events = sep < 0 ? "" : s.substring(sep + 3).trim();
                    b.append("== במערכת ==\n").append(hooks.isEmpty() ? "המודול לא נטען ב-system_server מאז האתחול האחרון.\nסמן 'מסגרת המערכת' בהיקף ואתחל." : hooks.replaceAll("boot=\\d+\\n?", "")).append("\n\n");
                    b.append("== נחסם / הותר ==\n").append(events.isEmpty() ? "אין עדיין. נסה 'סגור הכל' ופתח שוב." : events).append("\n\n");
                    b.append("== הריגות של מוגנות (am_kill) ==\n").append(kills == null || kills.trim().isEmpty() ? "אין" : kills.trim());
                    out = b.toString();
                }
                final String result = out;
                runOnUiThread(new Runnable() {
                    public void run() {
                        TextView t = text(result, 12, TXT);
                        t.setTextIsSelectable(true);
                        t.setTextDirection(View.TEXT_DIRECTION_LTR);
                        t.setTypeface(Typeface.MONOSPACE);
                        t.setPadding(dp(18), dp(8), dp(18), dp(8));
                        ScrollView sv = new ScrollView(MainActivity.this);
                        sv.addView(t);
                        new AlertDialog.Builder(MainActivity.this).setTitle("יומן").setView(sv).setPositiveButton("סגור", null).show();
                    }
                });
            }
        }).start();
    }
}
