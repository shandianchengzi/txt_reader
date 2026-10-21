package io.github.shandianchengzi.txtreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider exposing only the single verified update APK to Android's installer. */
public final class UpdateApkProvider extends ContentProvider {
    private static final String APK_PATH = "/release.apk";
    private static final String MIME = "application/vnd.android.package-archive";
    @Override public boolean onCreate() { return true; }
    private File checkedFile(Uri uri) throws FileNotFoundException {
        if (!APK_PATH.equals(uri.getPath()) ||
                !("content".equals(uri.getScheme()))) {
            throw new FileNotFoundException("Unknown update file");
        }
        File path = new File(new File(getContext().getCacheDir(), "updates"), "release.apk");
        if (!path.isFile()) throw new FileNotFoundException("Update not downloaded");
        return path;
    }
    @Override public String getType(Uri uri) {
        return APK_PATH.equals(uri.getPath()) ? MIME : null;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only update");
        return ParcelFileDescriptor.open(checkedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        try {
            File file = checkedFile(uri);
            if (projection == null) projection = new String[]{
                    OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(projection);
            Object[] values = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(projection[i]))
                    values[i] = "txt-reader-update.apk";
                else if (OpenableColumns.SIZE.equals(projection[i]))
                    values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
