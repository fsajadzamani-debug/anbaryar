package com.sajad.anbaryar;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import org.json.JSONObject;
import java.util.ArrayList;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_FILE = 1;
    private static final int REQ_VOICE = 2;

    /** Injected after every page load: catches blob-URL downloads (the web app's Excel/CSV/PNG
     *  export buttons use <a download> + URL.createObjectURL) and routes them through the
     *  native Bridge.saveFile so they land in the real Downloads folder instead of doing
     *  nothing (a bare WebView can't follow blob: URLs on its own). Also wires window.print()
     *  to Android's own print dialog, which renders the page correctly (no 90°-rotation
     *  quirk from the WebView's own non-existent print support). */
    private static final String BRIDGE_JS =
        "(function(){\n" +
        "  if (window.__abBridged) return; window.__abBridged = true;\n" +
        "  document.addEventListener('click', function(e){\n" +
        "    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;\n" +
        "    if (!a || !a.href || a.href.indexOf('blob:') !== 0 || !window.AndroidBridge) return;\n" +
        "    e.preventDefault();\n" +
        "    fetch(a.href).then(function(r){ return r.blob(); }).then(function(blob){\n" +
        "      var reader = new FileReader();\n" +
        "      reader.onload = function(){\n" +
        "        var base64 = String(reader.result).split(',')[1] || '';\n" +
        "        window.AndroidBridge.saveFile(a.download || 'file', base64, blob.type || 'application/octet-stream');\n" +
        "      };\n" +
        "      reader.readAsDataURL(blob);\n" +
        "    });\n" +
        "  }, true);\n" +
        "  window.print = function(){ if (window.AndroidBridge) window.AndroidBridge.printPage(); };\n" +
        "})();";

    private WebView web;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(BRIDGE_JS, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                String[] types = params.getAcceptTypes();
                if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) {
                    pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "text/csv", "*/*"
                    });
                }
                try {
                    startActivityForResult(Intent.createChooser(pick, "انتخاب فایل"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                String text = (r != null && !r.isEmpty()) ? r.get(0) : "";
                web.evaluateJavascript("window.abVoice&&window.abVoice(" + JSONObject.quote(text) + ")", null);
            } else {
                web.evaluateJavascript("window.abVoice&&window.abVoice('')", null);
            }
            return;
        }
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            } else if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                result = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.abBack&&window.abBack())?'1':'0'", value -> {
            if (value == null || !value.contains("1")) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    private class Bridge {
        /** Saves an exported file (Excel template, CSV export, the print-ready PNG, ...)
         *  into the device's real Downloads folder. */
        @JavascriptInterface
        public boolean saveFile(String name, String base64, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { os.write(bytes); }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { os.write(bytes); }
                }
                runOnUiThread(() -> android.widget.Toast.makeText(MainActivity.this,
                        "فایل در پوشه Download ذخیره شد: " + name, android.widget.Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean shareFile(String name, String base64, String mime, String text) {
            try {
                File dir = new File(getCacheDir(), "shared");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File out = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(out)) { os.write(Base64.decode(base64, Base64.DEFAULT)); }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", out);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                if (text != null && !text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(send, "ارسال فایل")));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens Google's native Persian speech input; the recognized text comes back
         *  through window.abVoice() and fills the search box. */
        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "نام کالا را بگویید");
                try {
                    startActivityForResult(i, REQ_VOICE);
                } catch (Exception e) {
                    web.evaluateJavascript("window.abVoice&&window.abVoice('')", null);
                    android.widget.Toast.makeText(MainActivity.this,
                        "برنامه تشخیص گفتار گوگل روی گوشی نیست", android.widget.Toast.LENGTH_LONG).show();
                }
            });
        }

        /** Opens Android's native print dialog on the current page — used for the حواله
         *  form print and any other print button, instead of the WebView's broken window.print(). */
        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                if (pm == null) return;
                String jobName = "Anbaryar-" + System.currentTimeMillis();
                pm.print(jobName, web.createPrintDocumentAdapter(jobName), new PrintAttributes.Builder().build());
            });
        }
    }
}
