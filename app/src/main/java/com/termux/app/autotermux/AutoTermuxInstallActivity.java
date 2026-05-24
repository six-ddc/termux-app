package com.termux.app.autotermux;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class AutoTermuxInstallActivity extends Activity {

    private static final String LOG_TAG = "AutoTermuxInstall";
    private static final String ASSET_APK_PATH = "autotermux/AutoTermux.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final int REQUEST_INSTALL_PACKAGE = 7001;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, R.string.autotermux_install_permission_required, Toast.LENGTH_LONG).show();
                startActivity(new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
                ));
                finish();
                return;
            }

            File apkFile = copyEmbeddedApkToCache();
            launchPackageInstaller(apkFile);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to launch AutoTermux installer", e);
            Toast.makeText(this, getString(R.string.autotermux_install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private File copyEmbeddedApkToCache() throws Exception {
        File target = AutoTermuxApkProvider.getApkFile(this);
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent.getAbsolutePath());
        }

        try (InputStream input = getAssets().open(ASSET_APK_PATH);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[1024 * 256];
            int readBytes;
            while ((readBytes = input.read(buffer)) != -1) {
                output.write(buffer, 0, readBytes);
            }
        }

        if (!target.isFile() || target.length() == 0L) {
            throw new IllegalStateException("Embedded AutoTermux APK is empty");
        }
        return target;
    }

    private void launchPackageInstaller(File apkFile) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(AutoTermuxApkProvider.getApkUri(this), APK_MIME_TYPE);
        installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Logger.logInfo(LOG_TAG, "Launching AutoTermux installer for " + apkFile.getAbsolutePath());
        startActivityForResult(installIntent, REQUEST_INSTALL_PACKAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PACKAGE) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, R.string.autotermux_install_complete, Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }
}
