package com.termux.app.autotermux;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

public final class AutoTermuxApkProvider extends ContentProvider {

    static final String APK_FILE_NAME = "AutoTermux.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    static File getApkFile(Context context) {
        return new File(new File(context.getCacheDir(), "autotermux"), APK_FILE_NAME);
    }

    static Uri getApkUri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".autotermux.apk/" + APK_FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        ensureSupportedUri(uri);
        return APK_MIME_TYPE;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        ensureSupportedUri(uri);
        Context context = getContext();
        File apkFile = context == null ? null : getApkFile(context);
        MatrixCursor cursor = new MatrixCursor(new String[]{
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        });
        cursor.addRow(new Object[]{APK_FILE_NAME, apkFile != null && apkFile.isFile() ? apkFile.length() : 0L});
        return cursor;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        ensureSupportedUri(uri);
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("AutoTermux APK provider is read-only");
        }
        return ParcelFileDescriptor.open(getRequiredApkFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File getRequiredApkFile() throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Provider context is not available");
        }
        File apkFile = getApkFile(context);
        if (!apkFile.isFile()) {
            throw new FileNotFoundException(apkFile.getAbsolutePath());
        }
        return apkFile;
    }

    private void ensureSupportedUri(Uri uri) {
        if (!("/" + APK_FILE_NAME).equals(uri.getPath())) {
            throw new IllegalArgumentException("Unsupported AutoTermux APK URI: " + uri);
        }
    }
}
