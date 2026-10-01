package app.koma.reader;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Trouve les BD dans les dossiers choisis par l'utilisateur (Storage Access Framework)
 * et prépare leurs pages dans le cache de l'app, d'où la WebView les charge.
 */
final class Library {

    interface ScanProgress { void found(int n); }
    interface Progress { void step(int done, int total); }

    static final String URL_BASE = "https://appassets.androidplatform.net/cache/";
    private static final Pattern IMG = Pattern.compile("(?i).*\\.(jpe?g|png|webp|gif|bmp|avif)$");
    private static final int KEEP_EXTRACTED = 12;
    private static final int COVER_W = 360;
    private static final Charset CP437 = cp437();

    private static Charset cp437() {
        try { return Charset.forName("IBM437"); }
        catch (Exception e) { return StandardCharsets.ISO_8859_1; }
    }

    final ExecutorService exec = Executors.newSingleThreadExecutor();
    final ExecutorService covers = Executors.newSingleThreadExecutor();

    private final Context ctx;
    private final ContentResolver cr;
    private final SharedPreferences prefs;
    private final Map<String, JSONObject> books = new ConcurrentHashMap<>();

    Library(Context c) {
        ctx = c.getApplicationContext();
        cr = ctx.getContentResolver();
        prefs = ctx.getSharedPreferences("koma", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(prefs.getString("books", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.getJSONObject(i);
                books.put(b.getString("id"), b);
            }
        } catch (Exception ignored) { }
    }

    File cacheRoot() {
        File f = new File(ctx.getCacheDir(), "koma");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    /* ======================= dossiers ======================= */

    private JSONArray folders() {
        try { return new JSONArray(prefs.getString("folders", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    String foldersJson() { return folders().toString(); }

    synchronized void addFolder(Uri tree) {
        JSONArray a = folders();
        String u = tree.toString();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && u.equals(o.optString("uri"))) return;
        }
        a.put(obj("uri", u, "name", folderName(tree)));
        prefs.edit().putString("folders", a.toString()).apply();
    }

    synchronized void removeFolder(String uri) {
        JSONArray a = folders(), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !uri.equals(o.optString("uri"))) keep.put(o);
        }
        prefs.edit().putString("folders", keep.toString()).apply();
        try { cr.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) { }
        for (JSONObject b : new ArrayList<>(books.values())) {
            if (uri.equals(b.optString("root"))) books.remove(b.optString("id"));
        }
        saveBooks();
    }

    private static String folderName(Uri tree) {
        String id;
        try { id = DocumentsContract.getTreeDocumentId(tree); }
        catch (Exception e) { return "Dossier"; }
        int i = id.indexOf(':');
        String p = i >= 0 ? id.substring(i + 1) : id;
        if (p.isEmpty()) return id.startsWith("primary") ? "Stockage interne" : "Carte SD";
        return p;
    }

    /* ======================= scan ======================= */

    String scan(ScanProgress sp) {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        JSONArray fs = folders();
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null) continue;
            String u = f.optString("uri");
            try {
                Uri tree = Uri.parse(u);
                String root = DocumentsContract.getTreeDocumentId(tree);
                String name = f.optString("name");
                int slash = name.lastIndexOf('/');
                String shortName = slash >= 0 ? name.substring(slash + 1) : name;
                walk(tree, u, root, shortName, shortName, 0, found, sp);
            } catch (Exception ignored) {
                // permission retirée ou dossier supprimé : on passe au suivant
            }
        }
        for (JSONObject b : books.values()) {
            if (b.optBoolean("external")) found.put(b.optString("id"), b);
        }
        books.clear();
        books.putAll(found);
        saveBooks();
        return booksJson();
    }

    private void walk(Uri tree, String rootUri, String docId, String name, String parentName, int depth,
                      Map<String, JSONObject> out, ScanProgress sp) {
        if (depth > 10) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> dirs = new ArrayList<>();
        int images = 0;
        long newest = 0;
        String[] proj = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = cr.query(children, proj, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0), dn = c.getString(1), mt = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                long mod = c.isNull(4) ? 0 : c.getLong(4);
                if (dn == null || dn.startsWith(".")) continue;
                if (Document.MIME_TYPE_DIR.equals(mt)) { dirs.add(new String[]{id, dn}); continue; }
                String kind = kindOf(dn);
                if (kind != null) {
                    Uri du = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    String bid = hash(du.toString());
                    out.put(bid, obj("id", bid, "title", stripExt(dn), "series", name, "kind", kind,
                            "uri", du.toString(), "root", rootUri, "size", size, "mtime", mod));
                    if (out.size() % 10 == 0) sp.found(out.size());
                } else if (IMG.matcher(dn).matches()) {
                    images++;
                    newest = Math.max(newest, mod);
                }
            }
        } catch (Exception e) {
            return;
        }
        if (images > 0) {
            Uri du = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
            String bid = hash(du.toString());
            out.put(bid, obj("id", bid, "title", name, "series", parentName, "kind", "dir",
                    "uri", du.toString(), "tree", tree.toString(), "doc", docId, "root", rootUri,
                    "size", images, "mtime", newest));
        }
        for (String[] d : dirs) walk(tree, rootUri, d[0], d[1], name, depth + 1, out, sp);
    }

    String registerExternal(Uri uri) throws IOException {
        String name = null;
        long size = 0;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                size = c.isNull(1) ? 0 : c.getLong(1);
            }
        } catch (Exception ignored) { }
        if (name == null) name = uri.getLastPathSegment() == null ? "Album" : uri.getLastPathSegment();
        String kind = kindOf(name);
        if (kind == null) {
            String type = cr.getType(uri);
            if (type == null) type = "";
            if (type.contains("pdf")) kind = "pdf";
            else if (type.contains("rar")) kind = "rar";
            else kind = "zip";
        }
        String id = hash(uri.toString());
        JSONObject b = obj("id", id, "title", stripExt(name), "series", "Ouverts depuis un fichier", "kind", kind,
                "uri", uri.toString(), "size", size, "mtime", System.currentTimeMillis(), "external", true);
        books.put(id, b);
        saveBooks();
        return b.toString();
    }

    String booksJson() {
        JSONArray a = new JSONArray();
        for (JSONObject b : books.values()) a.put(b);
        return a.toString();
    }

    String cachedBooksJson() { return booksJson(); }

    private void saveBooks() { prefs.edit().putString("books", booksJson()).apply(); }

    /* ======================= ouverture ======================= */

    String open(String id, Progress p) throws Exception {
        JSONObject b = books.get(id);
        if (b == null) throw new FileNotFoundException("album introuvable, actualise la bibliothèque");
        File dir = new File(cacheRoot(), "b/" + id);
        File done = new File(dir, ".done");
        if (!done.exists()) {
            deleteRec(dir);
            dir.mkdirs();
            List<String> names;
            switch (b.optString("kind")) {
                case "zip": names = extractZip(b, dir, p); break;
                case "rar": names = extractRar(b, dir, p); break;
                case "pdf": names = extractPdf(b, dir, p); break;
                default: names = extractDir(b, dir, p); break;
            }
            if (names.isEmpty()) { deleteRec(dir); throw new IOException("aucune image trouvée dedans"); }
            try (OutputStream o = new FileOutputStream(done)) {
                o.write(String.join("\n", names).getBytes(StandardCharsets.UTF_8));
            }
        }
        dir.setLastModified(System.currentTimeMillis());
        trim(id);
        String txt = new String(readAll(new FileInputStream(done)), StandardCharsets.UTF_8);
        JSONArray pages = new JSONArray();
        for (String n : txt.split("\n")) if (!n.isEmpty()) pages.put(URL_BASE + "b/" + id + "/" + n);
        return obj("pages", pages).toString();
    }

    private List<String> extractZip(JSONObject b, File dir, Progress p) throws Exception {
        Uri uri = Uri.parse(b.optString("uri"));
        long total = b.optLong("size", 0);
        for (Charset cs : new Charset[]{StandardCharsets.UTF_8, CP437}) {
            List<String[]> got = new ArrayList<>();
            InputStream raw = cr.openInputStream(uri);
            if (raw == null) throw new FileNotFoundException("fichier illisible");
            CountingInputStream cin = new CountingInputStream(raw);
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(cin, 1 << 16), cs)) {
                ZipEntry e;
                int k = 0, last = -1;
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    if (e.isDirectory() || hidden(n) || !IMG.matcher(n).matches()) continue;
                    String fn = String.format(Locale.ROOT, "%05d%s", k++, ext(n));
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(dir, fn)))) {
                        copy(zin, o);
                    }
                    got.add(new String[]{n, fn});
                    if (total > 0) {
                        int pct = (int) Math.min(99, cin.count * 100 / total);
                        if (pct != last) { last = pct; p.step(pct, 100); }
                    }
                }
                return sorted(got);
            } catch (IllegalArgumentException badName) {
                cleanDir(dir); // noms de fichiers non UTF-8 : on réessaie en IBM437
            } catch (ZipException ze) {
                cleanDir(dir);
                return extractZipFile(uri, dir, p); // archive que le mode flux ne sait pas lire
            }
        }
        return extractZipFile(uri, dir, p);
    }

    private List<String> extractZipFile(Uri uri, File dir, Progress p) throws Exception {
        File tmp = copyToTemp(uri);
        try {
            ZipFile zf;
            try { zf = new ZipFile(tmp); }
            catch (Exception e) { zf = new ZipFile(tmp, CP437); }
            try {
                List<ZipEntry> list = new ArrayList<>();
                for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!e.isDirectory() && !hidden(e.getName()) && IMG.matcher(e.getName()).matches()) list.add(e);
                }
                Collections.sort(list, (a, c) -> NATURAL.compare(a.getName(), c.getName()));
                List<String> names = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    ZipEntry e = list.get(i);
                    String fn = String.format(Locale.ROOT, "%05d%s", i, ext(e.getName()));
                    try (InputStream in = zf.getInputStream(e);
                         OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(dir, fn)))) {
                        copy(in, o);
                    }
                    names.add(fn);
                    p.step(i + 1, list.size());
                }
                return names;
            } finally {
                zf.close();
            }
        } finally {
            tmp.delete();
        }
    }

    private List<String> extractRar(JSONObject b, File dir, Progress p) throws Exception {
        File tmp = copyToTemp(Uri.parse(b.optString("uri")));
        try (Archive a = new Archive(tmp)) {
            List<FileHeader> hs = rarImages(a);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < hs.size(); i++) {
                FileHeader h = hs.get(i);
                String fn = String.format(Locale.ROOT, "%05d%s", i, ext(h.getFileName()));
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(dir, fn)))) {
                    a.extractFile(h, o);
                }
                names.add(fn);
                p.step(i + 1, hs.size());
            }
            return names;
        } finally {
            tmp.delete();
        }
    }

    private static List<FileHeader> rarImages(Archive a) {
        List<FileHeader> hs = new ArrayList<>();
        for (FileHeader h : a.getFileHeaders()) {
            String n = h.getFileName();
            if (!h.isDirectory() && n != null && !hidden(n) && IMG.matcher(n).matches()) hs.add(h);
        }
        Collections.sort(hs, (x, y) -> NATURAL.compare(x.getFileName(), y.getFileName()));
        return hs;
    }

    private List<String> extractPdf(JSONObject b, File dir, Progress p) throws Exception {
        Uri uri = Uri.parse(b.optString("uri"));
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new FileNotFoundException("PDF illisible");
        List<String> names = new ArrayList<>();
        try (PdfRenderer r = new PdfRenderer(pfd)) {
            int n = r.getPageCount();
            for (int i = 0; i < n; i++) {
                String fn = String.format(Locale.ROOT, "%05d.jpg", i);
                try (PdfRenderer.Page pg = r.openPage(i)) {
                    Bitmap bm = renderPdfPage(pg, 1600);
                    saveJpeg(bm, new File(dir, fn), 88);
                    bm.recycle();
                }
                names.add(fn);
                p.step(i + 1, n);
            }
        }
        return names;
    }

    private static Bitmap renderPdfPage(PdfRenderer.Page pg, int w) {
        int h = Math.max(1, Math.round(w * (float) pg.getHeight() / Math.max(1, pg.getWidth())));
        if (h > 4200) { w = Math.round(w * 4200f / h); h = 4200; }
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bm.eraseColor(Color.WHITE);
        pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bm;
    }

    private List<String[]> dirImages(JSONObject b) {
        Uri tree = Uri.parse(b.optString("tree"));
        String doc = b.optString("doc");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        List<String[]> imgs = new ArrayList<>();
        try (Cursor c = cr.query(children, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                String id = c.getString(0), dn = c.getString(1);
                if (dn != null && !dn.startsWith(".") && IMG.matcher(dn).matches()) {
                    imgs.add(new String[]{dn, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()});
                }
            }
        }
        Collections.sort(imgs, (x, y) -> NATURAL.compare(x[0], y[0]));
        return imgs;
    }

    private List<String> extractDir(JSONObject b, File dir, Progress p) throws Exception {
        List<String[]> imgs = dirImages(b);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < imgs.size(); i++) {
            String fn = String.format(Locale.ROOT, "%05d%s", i, ext(imgs.get(i)[0]));
            try (InputStream in = cr.openInputStream(Uri.parse(imgs.get(i)[1]));
                 OutputStream o = new BufferedOutputStream(new FileOutputStream(new File(dir, fn)))) {
                if (in == null) continue;
                copy(in, o);
            }
            names.add(fn);
            p.step(i + 1, imgs.size());
        }
        return names;
    }

    /* ======================= couvertures ======================= */

    String cover(String id) throws Exception {
        File f = new File(cacheRoot(), "c/" + id + ".jpg");
        String url = URL_BASE + "c/" + id + ".jpg";
        if (f.exists() && f.length() > 0) return url;
        JSONObject b = books.get(id);
        if (b == null) return null;
        f.getParentFile().mkdirs();
        Bitmap bm = null;

        File done = new File(cacheRoot(), "b/" + id + "/.done");
        if (done.exists()) {
            String first = new String(readAll(new FileInputStream(done)), StandardCharsets.UTF_8).split("\n")[0];
            bm = decodeSampled(readAll(new FileInputStream(new File(done.getParentFile(), first))));
        } else {
            Uri uri = Uri.parse(b.optString("uri"));
            switch (b.optString("kind")) {
                case "zip": {
                    InputStream raw = cr.openInputStream(uri);
                    if (raw == null) return null;
                    try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16), CP437)) {
                        ZipEntry e;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory() && !hidden(e.getName()) && IMG.matcher(e.getName()).matches()) {
                                bm = decodeSampled(readAll(zin));
                                break;
                            }
                        }
                    }
                    break;
                }
                case "rar": {
                    File tmp = copyToTemp(uri);
                    try (Archive a = new Archive(tmp)) {
                        List<FileHeader> hs = rarImages(a);
                        if (!hs.isEmpty()) {
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            a.extractFile(hs.get(0), bo);
                            bm = decodeSampled(bo.toByteArray());
                        }
                    } finally {
                        tmp.delete();
                    }
                    break;
                }
                case "pdf": {
                    ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
                    if (pfd == null) return null;
                    try (PdfRenderer r = new PdfRenderer(pfd); PdfRenderer.Page pg = r.openPage(0)) {
                        bm = renderPdfPage(pg, COVER_W);
                    }
                    break;
                }
                default: {
                    List<String[]> imgs = dirImages(b);
                    if (!imgs.isEmpty()) {
                        InputStream in = cr.openInputStream(Uri.parse(imgs.get(0)[1]));
                        if (in != null) bm = decodeSampled(readAll(in));
                    }
                }
            }
        }
        if (bm == null) return null;
        saveJpeg(bm, f, 82);
        bm.recycle();
        return url;
    }

    private static Bitmap decodeSampled(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= COVER_W) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        if (bm == null) return null;
        if (bm.getWidth() > COVER_W) {
            int h = Math.round(bm.getHeight() * (COVER_W / (float) bm.getWidth()));
            Bitmap sc = Bitmap.createScaledBitmap(bm, COVER_W, Math.max(1, h), true);
            if (sc != bm) bm.recycle();
            bm = sc;
        }
        return bm;
    }

    /** Fichier du cache correspondant à une URL de page servie à la WebView. */
    File fileForUrl(String url) {
        if (url == null || !url.startsWith(URL_BASE)) return null;
        try {
            File root = cacheRoot().getCanonicalFile();
            File f = new File(root, url.substring(URL_BASE.length())).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath()) || !f.isFile()) return null;
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    /* ======================= cache ======================= */

    private void trim(String keep) {
        File[] dirs = new File(cacheRoot(), "b").listFiles();
        if (dirs == null || dirs.length <= KEEP_EXTRACTED) return;
        Arrays.sort(dirs, (a, c) -> Long.compare(c.lastModified(), a.lastModified()));
        for (int i = KEEP_EXTRACTED; i < dirs.length; i++) {
            if (!dirs[i].getName().equals(keep)) deleteRec(dirs[i]);
        }
    }

    void clearCache() {
        deleteRec(new File(cacheRoot(), "b"));
        deleteRec(new File(cacheRoot(), "c"));
    }

    private static void cleanDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) deleteRec(f);
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteRec(c);
        f.delete();
    }

    private File copyToTemp(Uri uri) throws IOException {
        File t = File.createTempFile("koma", ".tmp", ctx.getCacheDir());
        try (InputStream in = cr.openInputStream(uri); OutputStream o = new FileOutputStream(t)) {
            if (in == null) throw new FileNotFoundException("fichier illisible");
            copy(in, o);
        }
        return t;
    }

    /* ======================= utilitaires ======================= */

    private static List<String> sorted(List<String[]> got) {
        Collections.sort(got, (a, c) -> NATURAL.compare(a[0], c[0]));
        List<String> out = new ArrayList<>();
        for (String[] g : got) out.add(g[1]);
        return out;
    }

    static final Comparator<String> NATURAL = (x, y) -> {
        String a = x.toLowerCase(Locale.ROOT), b = y.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?!$)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?!$)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    };

    private static String kindOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".cbz") || n.endsWith(".zip")) return "zip";
        if (n.endsWith(".cbr") || n.endsWith(".rar")) return "rar";
        if (n.endsWith(".pdf")) return "pdf";
        return null;
    }

    private static boolean hidden(String path) {
        if (path.contains("__MACOSX")) return true;
        int s = path.lastIndexOf('/');
        return path.substring(s + 1).startsWith(".");
    }

    private static String ext(String name) {
        int d = name.lastIndexOf('.');
        String e = d >= 0 ? name.substring(d).toLowerCase(Locale.ROOT) : ".jpg";
        return e.length() > 6 ? ".jpg" : e;
    }

    private static String stripExt(String name) {
        int d = name.lastIndexOf('.');
        String s = d > 0 ? name.substring(0, d) : name;
        return s.replace('_', ' ').trim();
    }

    private static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) { }
        return o;
    }

    private static void saveJpeg(Bitmap bm, File f, int q) throws IOException {
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
            bm.compress(Bitmap.CompressFormat.JPEG, q, o);
        }
    }

    private static void copy(InputStream in, OutputStream o) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        copy(in, bo);
        if (!(in instanceof ZipInputStream)) in.close();
        return bo.toByteArray();
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count = 0;
        CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException {
            int r = super.read();
            if (r >= 0) count++;
            return r;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) count += r;
            return r;
        }
        @Override public long skip(long n) throws IOException {
            long r = super.skip(n);
            count += r;
            return r;
        }
    }
}
