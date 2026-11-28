package nl.atlas.shareproof.publisher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

public final class FixtureProvider extends ContentProvider {
    static final Uri PROBE = Uri.parse("content://nl.atlas.shareproof.publisher.proof/probe");
    static final byte[] PAYLOAD = "atlas-share-proof-v1\n".getBytes(StandardCharsets.UTF_8);
    static final String MIME = "application/octet-stream";
    @Override public boolean onCreate() { return true; }
    private void check(Uri uri) {
        if (!PROBE.equals(uri)) throw new SecurityException("unknown synthetic resource");
    }
    private File payload() { return new File(getContext().getFilesDir(), "synthetic-payload"); }
    @Override public String getType(Uri uri) { check(uri); return MIME; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        check(uri);
        if (selection != null || selectionArgs != null || sortOrder != null
                || projection == null || projection.length != 2
                || !OpenableColumns.DISPLAY_NAME.equals(projection[0])
                || !OpenableColumns.SIZE.equals(projection[1])) throw new SecurityException("projection");
        if (!payload().isFile()) throw new SecurityException("not committed");
        MatrixCursor c = new MatrixCursor(projection);
        c.addRow(new Object[]{"synthetic.bin", PAYLOAD.length});
        return c;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        check(uri);
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(payload(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new SecurityException("read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new SecurityException("read only");
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new SecurityException("read only"); }
}
