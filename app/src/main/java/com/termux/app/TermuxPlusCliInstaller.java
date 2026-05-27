package com.termux.app;

import android.content.Context;
import android.system.Os;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

final class TermuxPlusCliInstaller {
    private static final String LOG_TAG = "TermuxPlusCliInstaller";
    private static final String TERMUXPLUS_BIN_ASSET_DIR_PATH = "termuxplus/bin";
    private static final String[] CLI_NAMES = new String[] {"tp-android", "tp-home"};
    private static final int EXECUTABLE_FILE_MODE = 0700;

    private TermuxPlusCliInstaller() {}

    static void syncBundledCliIfNeeded(Context context) {
        for (String cliName : CLI_NAMES)
            syncBundledCliIfNeeded(context, cliName);
    }

    private static void syncBundledCliIfNeeded(Context context, String cliName) {
        try {
            File targetFile = new File(TERMUX_PREFIX_DIR_PATH + "/bin/" + cliName);
            File targetDirectory = targetFile.getParentFile();
            if (targetDirectory == null || !targetDirectory.isDirectory()) {
                return;
            }

            byte[] bundledCli = readAsset(context, TERMUXPLUS_BIN_ASSET_DIR_PATH + "/" + cliName);
            if (targetFile.isFile() && Arrays.equals(sha256(bundledCli), sha256(targetFile))) {
                Os.chmod(targetFile.getAbsolutePath(), EXECUTABLE_FILE_MODE);
                return;
            }

            File tempFile = new File(targetDirectory, "." + cliName + ".tmp");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
                outputStream.write(bundledCli);
            }
            Os.chmod(tempFile.getAbsolutePath(), EXECUTABLE_FILE_MODE);
            Os.rename(tempFile.getAbsolutePath(), targetFile.getAbsolutePath());
            Logger.logInfo(LOG_TAG, "Synced bundled " + cliName + " CLI to " + targetFile.getAbsolutePath());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to sync bundled " + cliName + " CLI", e);
        }
    }

    private static byte[] readAsset(Context context, String assetPath) throws Exception {
        try (InputStream inputStream = context.getAssets().open(assetPath);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(bytes);
    }

    private static byte[] sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
}
