package com.abhi.firelauncher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
  private static final int ICON_PX = 128, BANNER_W = 256, BANNER_H = 144;

  private WebView web;
  private PackageManager pm;
  private SharedPreferences meta;
  private final Map<String, ComponentName> comps = new ConcurrentHashMap<String, ComponentName>();
  /* Encoded icons/banners, built on demand when the page asks for them (not for every app at startup) */
  private final LruCache<String, byte[]> imgs = new LruCache<String, byte[]>(4 * 1024 * 1024) {
    @Override protected int sizeOf(String k, byte[] v) { return v.length; }
  };
  private final Handler ui = new Handler(Looper.getMainLooper());
  private ValueCallback<Uri[]> fileCb;
  private BroadcastReceiver rx;
  private boolean resumed, dirty;
  private final Runnable reload = new Runnable() { public void run() { dirty = false; web.reload(); } };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    pm = getPackageManager();
    meta = getSharedPreferences("apps", MODE_PRIVATE);
    getWindow().setBackgroundDrawable(null); /* the WebView is opaque, so a window background would just be overdraw */
    web = new WebView(this);
    web.setBackgroundColor(0xFF151515);
    web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    web.setVerticalScrollBarEnabled(false);
    web.setHorizontalScrollBarEnabled(false);
    web.setHapticFeedbackEnabled(false);
    setContentView(web);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true);
    s.setTextZoom(100); /* a TV with a big system font size must not rescale the layout */
    s.setSupportZoom(false); s.setBuiltInZoomControls(false);
    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if ("launcher.local".equals(u.getHost())) {
          String path = u.getPath();
          byte[] d = path != null && path.length() > 1 ? image(path.substring(1)) : null;
          if (d == null) d = new byte[0];
          return new WebResourceResponse(path != null && path.startsWith("/banner/") ? "image/jpeg" : "image/png", null, new ByteArrayInputStream(d));
        }
        return null;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
        fileCb = cb;
        try { startActivityForResult(p.createIntent(), 7); }
        catch (Exception e) { cb.onReceiveValue(null); fileCb = null; Toast.makeText(MainActivity.this, "No file manager found. Install one to pick a wallpaper.", Toast.LENGTH_LONG).show(); }
        return true;
      }
    });
    web.loadUrl("file:///android_asset/launcher.html");
    web.requestFocus();
    /* App installed/removed: reload the page, but only while we are on screen. If we are in the
       background (e.g. a video is playing), wait until the user comes back instead of burning CPU. */
    rx = new BroadcastReceiver() { public void onReceive(Context c, Intent i) {
      dirty = true;
      if (resumed) { ui.removeCallbacks(reload); ui.postDelayed(reload, 1000); }
    } };
    IntentFilter f = new IntentFilter();
    f.addAction(Intent.ACTION_PACKAGE_ADDED); f.addAction(Intent.ACTION_PACKAGE_REMOVED); f.addDataScheme("package");
    registerReceiver(rx, f);
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    if (req == 7 && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null; }
  }
  @Override protected void onResume() {
    super.onResume(); resumed = true; web.onResume(); web.requestFocus();
    if (dirty) { dirty = false; web.reload(); }
  }
  @Override protected void onPause() {
    resumed = false; ui.removeCallbacks(reload); web.onPause(); /* stop rendering while another app is in front */
    super.onPause();
  }
  @Override public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level >= TRIM_MEMORY_BACKGROUND) imgs.evictAll();
    else if (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN) imgs.trimToSize(imgs.maxSize() / 2);
  }
  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); web.evaluateJavascript("window.goHome&&goHome()", null); }
  @Override protected void onDestroy() {
    ui.removeCallbacks(reload);
    unregisterReceiver(rx);
    ViewGroup p = (ViewGroup) web.getParent();
    if (p != null) p.removeView(web);
    web.destroy(); /* hand the WebView's memory back instead of waiting for the process to die */
    super.onDestroy();
  }
  @Override public boolean onKeyDown(int code, KeyEvent e) {
    if (code == KeyEvent.KEYCODE_BACK) { web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))", null); return true; }
    if (code == KeyEvent.KEYCODE_MENU) { web.evaluateJavascript("document.getElementById('bSet').click()", null); return true; }
    return super.onKeyDown(code, e);
  }

  private Bitmap draw(Drawable d, int w, int h, int bg) {
    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    if (bg != 0) c.drawColor(bg);
    d.setBounds(0, 0, w, h); d.draw(c);
    return b;
  }

  private Drawable banner(ActivityInfo ai) {
    Drawable d = ai.loadBanner(pm);
    if (d == null) d = ai.applicationInfo.loadBanner(pm);
    return d;
  }

  /* Builds the PNG (icon) or JPEG (banner) for "icon/<pkg>" or "banner/<pkg>". Runs on a WebView IO thread. */
  private byte[] image(String key) {
    byte[] hit = imgs.get(key);
    if (hit != null) return hit;
    boolean isBanner = key.startsWith("banner/");
    if (!isBanner && !key.startsWith("icon/")) return null;
    ComponentName cn = comps.get(key.substring(isBanner ? 7 : 5));
    if (cn == null) return null;
    try {
      ByteArrayOutputStream o = new ByteArrayOutputStream(isBanner ? 16384 : 8192);
      Bitmap bm;
      if (isBanner) {
        Drawable dr = banner(pm.getActivityInfo(cn, 0));
        if (dr == null) return null;
        bm = draw(dr, BANNER_W, BANNER_H, 0xFF151515);
        bm.compress(Bitmap.CompressFormat.JPEG, 85, o);
      } else {
        bm = draw(pm.getActivityIcon(cn), ICON_PX, ICON_PX, 0);
        bm.compress(Bitmap.CompressFormat.PNG, 100, o);
      }
      bm.recycle();
      byte[] out = o.toByteArray();
      imgs.put(key, out);
      return out;
    } catch (OutOfMemoryError e) { imgs.evictAll(); return null; }
    catch (Exception e) { return null; }
  }

  /* Most prominent saturated colour of a small bitmap, which is the brand colour for nearly every app */
  private int[] brand(Bitmap b) {
    int w = b.getWidth(), h = b.getHeight(), n = w * h;
    int[] px = new int[n];
    b.getPixels(px, 0, w, 0, 0, w, h);
    b.recycle();
    float[] hsv = new float[3];
    double[] wt = new double[12], rs = new double[12], gs = new double[12], bs = new double[12];
    double ar = 0, ag = 0, ab = 0, an = 0;
    for (int i = 0; i < n; i++) {
      int p = px[i]; if (Color.alpha(p) < 200) continue;
      int pr = Color.red(p), pg = Color.green(p), pb = Color.blue(p);
      ar += pr; ag += pg; ab += pb; an++;
      Color.RGBToHSV(pr, pg, pb, hsv);
      if (hsv[1] < .3f || hsv[2] < .3f) continue;
      int k = ((int) (hsv[0] / 30f)) % 12; double q = hsv[1] * hsv[2];
      wt[k] += q; rs[k] += pr * q; gs[k] += pg * q; bs[k] += pb * q;
    }
    int best = -1; double bw = 0;
    for (int k = 0; k < 12; k++) if (wt[k] > bw) { bw = wt[k]; best = k; }
    if (best >= 0 && bw >= 6.0 * n / 1600.0) return new int[] { (int) (rs[best] / bw), (int) (gs[best] / bw), (int) (bs[best] / bw) };
    if (an > 0) return new int[] { (int) (ar / an), (int) (ag / an), (int) (ab / an) };
    return new int[] { 58, 64, 70 };
  }

  private String[] unpack(String v) {
    if (v == null) return null;
    String[] f = v.split("\t", 5);
    return f.length == 5 ? f : null;
  }

  private class Bridge {
    /* Returns [{name,pkg,color,ink,icon,banner}]. Label, banner flag and brand colour are cached on disk per
       app (keyed by the APK's timestamp and the language), so after the first run this is just a package query. */
    @JavascriptInterface public String getApps() {
      List<ResolveInfo> all = new ArrayList<ResolveInfo>();
      String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
      for (String c : cats) all.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(c), 0));
      String loc = Locale.getDefault().toString();
      SharedPreferences.Editor ed = null;
      Map<String, JSONObject> out = new LinkedHashMap<String, JSONObject>();
      for (ResolveInfo ri : all) {
        ActivityInfo ai = ri.activityInfo;
        String pkg = ai.packageName;
        if (out.containsKey(pkg) || pkg.equals(getPackageName())) continue;
        try {
          String stamp = String.valueOf(new File(ai.applicationInfo.sourceDir).lastModified());
          boolean cacheable = !"0".equals(stamp);
          String[] m = cacheable ? unpack(meta.getString(pkg, null)) : null;
          String label, hex; boolean hasBanner;
          if (m != null && m[0].equals(stamp) && m[1].equals(loc)) {
            hex = m[2]; hasBanner = "1".equals(m[3]); label = m[4];
          } else {
            label = ri.loadLabel(pm).toString().replace('\t', ' ').replace('\n', ' ');
            boolean maybe = ai.banner != 0 || ai.applicationInfo.banner != 0;
            Drawable bn = maybe ? banner(ai) : null;
            hasBanner = bn != null;
            int[] c = hasBanner ? brand(draw(bn, 64, 36, 0)) : brand(draw(ri.loadIcon(pm), 40, 40, 0));
            hex = String.format(Locale.US, "#%02X%02X%02X", c[0], c[1], c[2]);
            if (cacheable) {
              if (ed == null) ed = meta.edit();
              ed.putString(pkg, stamp + "\t" + loc + "\t" + hex + "\t" + (hasBanner ? "1" : "0") + "\t" + label);
            }
          }
          int col = Color.parseColor(hex);
          double lum = (.299 * Color.red(col) + .587 * Color.green(col) + .114 * Color.blue(col)) / 255;
          comps.put(pkg, new ComponentName(pkg, ai.name));
          JSONObject o = new JSONObject();
          o.put("name", label); o.put("pkg", pkg);
          o.put("color", hex); o.put("ink", lum > .62 ? "#151515" : "#ffffff");
          o.put("icon", "<img src=\"http://launcher.local/icon/" + pkg + "\" alt=\"\" decoding=\"async\">");
          o.put("banner", hasBanner ? "http://launcher.local/banner/" + pkg : "");
          out.put(pkg, o);
        } catch (Exception e) { }
      }
      if (ed != null) ed.apply();
      List<JSONObject> list = new ArrayList<JSONObject>(out.values());
      Collections.sort(list, new Comparator<JSONObject>() {
        public int compare(JSONObject a, JSONObject b) { return a.optString("name").compareToIgnoreCase(b.optString("name")); }
      });
      return new JSONArray(list).toString();
    }
    @JavascriptInterface public void launch(String pkg) {
      final ComponentName c = comps.get(pkg); if (c == null) return;
      ui.post(new Runnable() { public void run() {
        try { Intent i = new Intent(Intent.ACTION_MAIN); i.setComponent(c); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startActivity(i); }
        catch (Exception e) { Toast.makeText(MainActivity.this, "Couldn't open that app", Toast.LENGTH_SHORT).show(); }
      } });
    }
  }
}
