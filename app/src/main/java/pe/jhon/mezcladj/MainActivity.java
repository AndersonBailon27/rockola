package pe.jhon.mezcladj;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK = 7;
    private WebView web;
    private ValueCallback<Uri[]> pick;
    private FileOutputStream recOut;
    private File recFile;
    private String recExt = "webm";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF141518);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pick != null) pick.onReceiveValue(null);
                pick = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Elige tus canciones"), PICK);
                } catch (Exception e) {
                    pick = null;
                    return false;
                }
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || pick == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData c = data.getClipData();
            if (c != null) {
                out = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) out[i] = c.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        pick.onReceiveValue(out);
        pick = null;
    }

    @Override
    protected void onDestroy() {
        stopService(new Intent(this, MixService.class));
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private String save() throws Exception {
        String name = "Mezcla " + new SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(new Date()) + "." + recExt;
        String mime = recExt.equals("m4a") ? "audio/mp4" : "audio/webm";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Mezclas");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (u == null) throw new Exception("sin acceso");
            try (OutputStream o = getContentResolver().openOutputStream(u); InputStream in = new FileInputStream(recFile)) { copy(in, o); }
            v.clear();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Mezclas");
            dir.mkdirs();
            File f = new File(dir, name);
            try (OutputStream o = new FileOutputStream(f); InputStream in = new FileInputStream(recFile)) { copy(in, o); }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        }
        recFile.delete();
        return name;
    }

    private static void copy(InputStream in, OutputStream o) throws Exception {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
    }

    private class Bridge {
        @JavascriptInterface
        public void mixing(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            Intent i = new Intent(MainActivity.this, MixService.class);
            try {
                if (!on) stopService(i);
                else if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public synchronized boolean recBegin(String ext) {
            try {
                recExt = ext;
                recFile = new File(getCacheDir(), "grabando." + ext);
                recOut = new FileOutputStream(recFile);
                return true;
            } catch (Exception e) {
                recOut = null;
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void recChunk(String b64) {
            try {
                if (recOut != null) recOut.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public synchronized String recEnd() {
            try {
                if (recOut == null) return "No se pudo guardar la grabación.";
                recOut.close();
                recOut = null;
                return "Mezcla guardada en Música › Mezclas: " + save();
            } catch (Exception e) {
                return "No se pudo guardar la grabación.";
            }
        }
    }
}
