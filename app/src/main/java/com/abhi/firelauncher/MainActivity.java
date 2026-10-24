package com.abhi.firelauncher;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.*;
import android.provider.OpenableColumns;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
  private static final int ICON_PX = 128, BANNER_W = 256, BANNER_H = 144;
  private static final int FONT_REQUEST = 8, MAX_FONT_BYTES = 12 * 1024 * 1024;

  private WebView web;
  private PackageManager pm;
  private SharedPreferences meta;
  private SharedPreferences fonts;
  private final ExecutorService fontWorker = Executors.newSingleThreadExecutor();
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
    fonts = getSharedPreferences("fonts", MODE_PRIVATE);
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
          if (path != null && path.startsWith("/font/")) {
            String id = path.substring(6);
            String[] font = fontRecord(id);
            File file = font == null ? null : new File(fontDirectory(), id + "." + font[1]);
            try {
              if (file != null && file.isFile()) {
                return new WebResourceResponse(fontMime(font[1]), "UTF-8", new FileInputStream(file));
              }
            } catch (IOException e) {
              android.util.Log.e("GlassLauncher", "Unable to read imported font", e);
            }
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                Collections.<String, String>emptyMap(), new ByteArrayInputStream(new byte[0]));
          }
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
    super.onActivityResult(req, res, data);
    if (req == 7 && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null; }
    else if (req == FONT_REQUEST && res == RESULT_OK && data != null && data.getData() != null) {
      final Uri uri = data.getData();
      fontWorker.execute(new Runnable() { public void run() {
        try { importFont(uri); }
        catch (Exception e) { fontOperation(false, message(e, "Could not import that font file")); }
      } });
    }
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
    fontWorker.shutdownNow();
    unregisterReceiver(rx);
    ViewGroup p = (ViewGroup) web.getParent();
    if (p != null) p.removeView(web);
    web.destroy(); /* hand the WebView's memory back instead of waiting for the process to die */
    web = null;
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

  private File fontDirectory() {
    File dir = new File(getFilesDir(), "fonts");
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("Could not create the font storage folder");
    return dir;
  }

  private String[] fontRecord(String id) {
    if (id == null || !id.matches("[a-f0-9-]{36}")) return null;
    String record = fonts.getString("font." + id, null);
    if (record == null) return null;
    String[] fields = record.split("\t", 2);
    return fields.length == 2 ? fields : null;
  }

  private String fontMime(String format) {
    if ("woff2".equals(format)) return "font/woff2";
    if ("woff".equals(format)) return "font/woff";
    if ("otf".equals(format)) return "font/otf";
    return "font/ttf";
  }

  private String fontFormat(byte[] data) throws IOException {
    if (data.length < 4) throw new IOException("The selected file is too small to be a font");
    String signature = new String(data, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
    if (signature.equals("OTTO")) return "otf";
    if (signature.equals("wOFF")) return "woff";
    if (signature.equals("wOF2")) return "woff2";
    if (signature.equals("true") || signature.equals("typ1")
        || (data[0] == 0 && data[1] == 1 && data[2] == 0 && data[3] == 0)) return "ttf";
    throw new IOException("Unsupported font file. Choose a valid TTF, OTF, WOFF, or WOFF2 font");
  }

  private byte[] readFont(InputStream input) throws IOException {
    try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int total = 0, count;
      while ((count = in.read(buffer)) != -1) {
        total += count;
        if (total > MAX_FONT_BYTES) throw new IOException("Font files must be 12 MB or smaller");
        out.write(buffer, 0, count);
      }
      byte[] data = out.toByteArray();
      fontFormat(data);
      return data;
    }
  }

  private String displayName(Uri uri) {
    String name = null;
    Cursor cursor = getContentResolver().query(uri, null, null, null, null);
    if (cursor != null) {
      try {
        if (cursor.moveToFirst()) {
          int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (column >= 0) name = cursor.getString(column);
        }
      } finally { cursor.close(); }
    }
    if (name == null || name.trim().isEmpty()) name = "Imported font";
    int dot = name.lastIndexOf('.');
    if (dot > 0) name = name.substring(0, dot);
    return cleanFontName(name);
  }

  private String cleanFontName(String value) {
    String name = value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ").trim();
    if (name.length() > 60) name = name.substring(0, 60).trim();
    return name.isEmpty() ? "Custom font" : name;
  }

  private void saveFont(byte[] data, String name) throws IOException {
    String format = fontFormat(data), id = UUID.randomUUID().toString();
    File file = new File(fontDirectory(), id + "." + format);
    try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
    if (!fonts.edit().putString("font." + id, cleanFontName(name) + "\t" + format).commit()) {
      file.delete();
      throw new IOException("Could not save the font in the app");
    }
    fontOperation(true, "Added " + cleanFontName(name));
  }

  private void importFont(Uri uri) throws IOException {
    byte[] data = readFont(getContentResolver().openInputStream(uri));
    saveFont(data, displayName(uri));
  }

  private URL checkedFontUrl(String value) throws IOException {
    try {
      URL url = new URL(value);
      String protocol = url.getProtocol();
      if (!("https".equalsIgnoreCase(protocol) || "http".equalsIgnoreCase(protocol))
          || url.getHost().isEmpty() || url.getUserInfo() != null) {
        throw new IOException("Use a direct HTTP or HTTPS font file URL");
      }
      return url;
    } catch (java.net.MalformedURLException e) {
      throw new IOException("Enter a valid direct font file URL");
    }
  }

  private void downloadFont(String value) throws IOException {
    URL url = checkedFontUrl(value);
    boolean startedHttps = "https".equalsIgnoreCase(url.getProtocol());
    for (int redirects = 0; redirects <= 5; redirects++) {
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(20000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", "font/woff2,font/woff,font/ttf,font/otf,application/octet-stream");
      try {
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP
            || status == 307 || status == 308 || status == 303) {
          String location = connection.getHeaderField("Location");
          if (location == null || redirects == 5) throw new IOException("Font download redirected too many times");
          url = checkedFontUrl(new URL(url, location).toString());
          if (startedHttps && !"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Refusing an insecure redirect from HTTPS");
          }
          continue;
        }
        if (status < 200 || status >= 300) throw new IOException("Font download failed (HTTP " + status + ")");
        int length = connection.getContentLength();
        if (length > MAX_FONT_BYTES) throw new IOException("Font files must be 12 MB or smaller");
        String path = url.getPath();
        String name = path == null ? "" : URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), "UTF-8");
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        if (name.isEmpty()) name = "Downloaded font";
        saveFont(readFont(connection.getInputStream()), name);
        return;
      } finally { connection.disconnect(); }
    }
    throw new IOException("Font download redirected too many times");
  }

  private String message(Exception e, String fallback) {
    String text = e.getMessage();
    return text == null || text.trim().isEmpty() ? fallback : text;
  }

  private void fontOperation(final boolean success, final String text) {
    ui.post(new Runnable() { public void run() {
      if (web == null) return;
      web.evaluateJavascript("window.onFontOperation&&window.onFontOperation("
          + (success ? "true" : "false") + "," + JSONObject.quote(text) + ")", null);
    } });
  }

  private class Bridge {
    @JavascriptInterface public boolean isFireTv() {
      return "Amazon".equalsIgnoreCase(Build.MANUFACTURER)
          || (Build.MODEL != null && Build.MODEL.toUpperCase(Locale.US).startsWith("AFT"));
    }
    @JavascriptInterface public String getFonts() {
      JSONArray list = new JSONArray();
      Map<String, ?> records = fonts.getAll();
      for (Map.Entry<String, ?> entry : records.entrySet()) {
        if (!entry.getKey().startsWith("font.") || !(entry.getValue() instanceof String)) continue;
        String id = entry.getKey().substring(5);
        String[] record = fontRecord(id);
        if (record == null) continue;
        JSONObject font = new JSONObject();
        try {
          font.put("id", id); font.put("name", record[0]); font.put("format", record[1]);
          list.put(font);
        } catch (JSONException e) { android.util.Log.e("GlassLauncher", "Could not list imported font", e); }
      }
      return list.toString();
    }
    @JavascriptInterface public void chooseFontFile() {
      ui.post(new Runnable() { public void run() {
        try {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          intent.setType("*/*");
          intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
              "font/ttf", "font/otf", "font/woff", "font/woff2", "application/font-sfnt", "application/octet-stream"
          });
          startActivityForResult(intent, FONT_REQUEST);
        } catch (Exception e) { fontOperation(false, message(e, "Could not open the font picker")); }
      } });
    }
    @JavascriptInterface public void addFontFromUrl(final String url) {
      fontWorker.execute(new Runnable() { public void run() {
        try { downloadFont(url); }
        catch (Exception e) { fontOperation(false, message(e, "Could not download that font")); }
      } });
    }
    @JavascriptInterface public void removeFont(final String id) {
      ui.post(new Runnable() { public void run() {
        String[] record = fontRecord(id);
        if (record == null) { fontOperation(false, "That font is no longer installed"); return; }
        File file = new File(fontDirectory(), id + "." + record[1]);
        if (!fonts.edit().remove("font." + id).commit()) {
          fontOperation(false, "Could not remove that font from the app"); return;
        }
        if (file.exists() && !file.delete()) android.util.Log.w("GlassLauncher", "Could not remove font file " + file.getName());
        fontOperation(true, "Removed " + record[0]);
      } });
    }
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
