package vn.phantichcg;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Phân tích Cuộc gọi / SMS - bản Android (chạy offline, kết nối được máy chủ LAN).
 * Giao diện là trang web trong assets; dữ liệu lưu vào thư mục người dùng chọn:
 * kho.json (tài khoản), TepDaPhanTich/{mobi,vina,viettel}/ và KetQua/.
 */
public class MainActivity extends Activity {
    private static final int REQ_FOLDER = 101;
    private static final int REQ_FILE = 102;
    private static final int REQ_SAVEAS = 103;
    private static final String PREFS = "phantichcg";
    private static final String KEY_TREE = "treeUri";
    private static final String KHO_FILE = "kho.json";
    private static final String[] SUBS = {"TepDaPhanTich/mobi", "TepDaPhanTich/vina", "TepDaPhanTich/viettel", "KetQua"};

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingTree;
    private byte[] pendingSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel", "text/csv", "text/comma-separated-values",
                        "text/plain", "application/octet-stream"});
                try {
                    startActivityForResult(i, REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Store(), "AndroidStore");
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void js(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri[] res = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) res = new Uri[]{data.getData()};
            if (fileCallback != null) fileCallback.onReceiveValue(res);
            fileCallback = null;
            return;
        }
        if (requestCode == REQ_SAVEAS) {
            String msg = "";
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
                try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
                    os.write(pendingSave);
                    DocumentFile f = DocumentFile.fromSingleUri(this, data.getData());
                    msg = f != null && f.getName() != null ? f.getName() : "đã lưu";
                } catch (Exception e) {
                    Toast.makeText(this, "Không lưu được file: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingSave = null;
            js("window.__nativeSaved && window.__nativeSaved(" + JSONObject.quote(msg) + ")");
            return;
        }
        if (requestCode == REQ_FOLDER) {
            String json = "";
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    pendingTree = tree;
                    DocumentFile dir = DocumentFile.fromTreeUri(this, tree);
                    JSONObject o = new JSONObject();
                    o.put("folder", describe(tree));
                    o.put("hasData", dir != null && dir.findFile(KHO_FILE) != null);
                    json = o.toString();
                } catch (Exception e) {
                    Toast.makeText(this, "Không cấp được quyền cho thư mục này", Toast.LENGTH_LONG).show();
                }
            }
            js("window.__nativeChosen && window.__nativeChosen(" + JSONObject.quote(json) + ")");
        }
    }

    /* ---------- tiện ích lưu trữ ---------- */

    private Uri savedTree() {
        String u = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        if (u == null) return null;
        Uri tree = Uri.parse(u);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return tree;
        }
        return null;
    }

    private DocumentFile root() {
        Uri t = savedTree();
        return t == null ? null : DocumentFile.fromTreeUri(this, t);
    }

    private String describe(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int c = id.indexOf(':');
            String vol = c > 0 ? id.substring(0, c) : "";
            String p = c >= 0 ? id.substring(c + 1) : id;
            return ("primary".equalsIgnoreCase(vol) ? "Bộ nhớ máy" : "Thẻ nhớ " + vol) + "/" + p;
        } catch (Exception e) {
            return tree.toString();
        }
    }

    private static boolean okSub(String sub) {
        for (String s : SUBS) if (s.equals(sub)) return true;
        return false;
    }

    /** Lấy (hoặc tạo) thư mục con theo đường dẫn "A/B". */
    private DocumentFile subDir(DocumentFile parent, String path, boolean create) {
        DocumentFile d = parent;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile n = d.findFile(part);
            if (n == null || !n.isDirectory()) {
                if (!create) return null;
                n = d.createDirectory(part);
                if (n == null) return null;
            }
            d = n;
        }
        return d;
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private void writeBytes(DocumentFile dir, String name, byte[] bytes) throws Exception {
        DocumentFile f = dir.findFile(name);
        if (f == null) {
            f = dir.createFile(mimeOf(name), name);
            if (f == null) throw new Exception("không tạo được file " + name);
            // một số trình quản lý file tự thêm đuôi theo kiểu file, nên đổi lại đúng tên
            if (!name.equals(f.getName())) f.renameTo(name);
        }
        try (OutputStream os = getContentResolver().openOutputStream(f.getUri(), "wt")) {
            if (os == null) throw new Exception("không ghi được file " + name);
            os.write(bytes);
            os.flush();
        }
    }

    private byte[] readBytes(DocumentFile f) throws Exception {
        try (InputStream is = getContentResolver().openInputStream(f.getUri())) {
            if (is == null) return new byte[0];
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    /* ---------- cầu nối với giao diện ---------- */

    private class Store {
        @JavascriptInterface
        public String status() {
            try {
                JSONObject o = new JSONObject();
                Uri t = savedTree();
                o.put("platform", "android");
                o.put("configured", t != null);
                o.put("folder", t == null ? "" : describe(t));
                return o.toString();
            } catch (Exception e) {
                return "{\"platform\":\"android\",\"configured\":false}";
            }
        }

        @JavascriptInterface
        public void choose() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        Uri docs = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents");
                        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, docs);
                    } catch (Exception ignored) { }
                }
                try {
                    startActivityForResult(i, REQ_FOLDER);
                } catch (Exception e) {
                    web.evaluateJavascript("window.__nativeChosen && window.__nativeChosen('')", null);
                }
            });
        }

        @JavascriptInterface
        public String confirm() {
            try {
                if (pendingTree == null) return "{\"error\":\"Chưa chọn thư mục\"}";
                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                ed.putString(KEY_TREE, pendingTree.toString()).apply();
                DocumentFile r = DocumentFile.fromTreeUri(MainActivity.this, pendingTree);
                if (r == null || !r.canWrite()) return "{\"error\":\"Không ghi được vào thư mục này\"}";
                for (String s : SUBS) subDir(r, s, true);
                if (r.findFile(KHO_FILE) == null) writeBytes(r, KHO_FILE, "{}".getBytes(StandardCharsets.UTF_8));
                JSONObject o = new JSONObject();
                o.put("folder", describe(pendingTree));
                return o.toString();
            } catch (Exception e) {
                return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        @JavascriptInterface
        public String kvRead() {
            try {
                DocumentFile r = root();
                if (r == null) return "{}";
                DocumentFile f = r.findFile(KHO_FILE);
                if (f == null) f = r.findFile(KHO_FILE + ".bak");
                return f == null ? "{}" : new String(readBytes(f), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public synchronized String kvWrite(String json) {
            try {
                DocumentFile r = root();
                if (r == null) return "Chưa chọn thư mục dữ liệu";
                DocumentFile old = r.findFile(KHO_FILE);
                if (old != null) {
                    try { writeBytes(r, KHO_FILE + ".bak", readBytes(old)); } catch (Exception ignored) { }
                }
                writeBytes(r, KHO_FILE, json.getBytes(StandardCharsets.UTF_8));
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        @JavascriptInterface
        public String saveFile(String sub, String name, String b64) {
            try {
                if (!okSub(sub)) return "ERR: thư mục không hợp lệ";
                DocumentFile r = root();
                if (r == null) return "ERR: Chưa chọn thư mục dữ liệu";
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                DocumentFile d = subDir(r, sub, true);
                if (d == null) return "ERR: không tạo được thư mục " + sub;
                writeBytes(d, safe, Base64.decode(b64, Base64.DEFAULT));
                return describe(savedTree()) + "/" + sub + "/" + safe;
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String listFiles(String sub) {
            JSONArray a = new JSONArray();
            try {
                DocumentFile r = root();
                if (r == null || !okSub(sub)) return "[]";
                DocumentFile d = subDir(r, sub, false);
                if (d == null) return "[]";
                for (DocumentFile f : d.listFiles()) {
                    if (!f.isFile() || f.getName() == null || f.getName().startsWith(".")) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", f.getName());
                    o.put("size", f.length());
                    o.put("time", f.lastModified());
                    a.put(o);
                }
            } catch (Exception ignored) { }
            return a.toString();
        }

        @JavascriptInterface
        public String readFile(String sub, String name) {
            try {
                DocumentFile r = root();
                if (r == null || !okSub(sub)) return "ERR: Chưa chọn thư mục dữ liệu";
                DocumentFile d = subDir(r, sub, false);
                DocumentFile f = d == null ? null : d.findFile(name);
                if (f == null) return "ERR: Không tìm thấy file " + name;
                return Base64.encodeToString(readBytes(f), Base64.NO_WRAP);
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void saveAs(String name, String b64) {
            pendingSave = Base64.decode(b64, Base64.DEFAULT);
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mimeOf(name));
                i.putExtra(Intent.EXTRA_TITLE, name);
                try {
                    startActivityForResult(i, REQ_SAVEAS);
                } catch (Exception e) {
                    pendingSave = null;
                    web.evaluateJavascript("window.__nativeSaved && window.__nativeSaved('')", null);
                }
            });
        }
    }
}
