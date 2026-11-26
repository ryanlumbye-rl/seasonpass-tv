package dk.seasonpass.tvhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lader Mac'en skrive feeden ind i appens egen mappe:
 *
 *   content write --uri content://dk.seasonpass.tvhome.feed/feed.json < /data/local/tmp/feed.json
 *   content write --uri content://dk.seasonpass.tvhome.feed/img/0123abcd.jpg < /data/local/tmp/x.jpg
 *
 * Kun feed.json og img/<hex>.jpg, så en sti aldrig kan pege uden for mappen. Manifestet kræver
 * WRITE_SECURE_SETTINGS, som adb-shellen har og almindelige apps ikke kan få.
 */
public class FeedProvider extends ContentProvider {

    private static final Pattern IMAGE = Pattern.compile("[0-9a-f]{8,40}\\.jpg");

    static File dir(android.content.Context c) {
        File ext = c.getApplicationContext().getExternalFilesDir(null);
        if (ext != null) Images.covers = new File(ext, "covers");
        return new File(c.getFilesDir(), "feed");
    }

    private File file(Uri uri) throws FileNotFoundException {
        List<String> seg = uri.getPathSegments();
        String path = null;
        if (seg.size() == 1 && seg.get(0).equals("feed.json")) path = "feed.json";
        else if (seg.size() == 2 && seg.get(0).equals("img") && IMAGE.matcher(seg.get(1)).matches()) path = "img/" + seg.get(1);
        if (path == null) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(dir(getContext()), path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = file(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 1 && seg.get(0).equals("actions")) {   // content delete --uri …/actions: tøm køen
            Actions.clear(getContext());
            return 1;
        }
        try {
            return file(uri).delete() ? 1 : 0;
        } catch (FileNotFoundException e) {
            return 0;
        }
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
