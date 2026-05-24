package com.termux.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.system.Os;

import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

final class TermuxPlusHomeInstaller {
    private static final String LOG_TAG = "TermuxPlusHomeInstaller";

    private static final String PREFIX_HOME_SOURCE_DIR_PATH = TERMUX_PREFIX_DIR_PATH + "/share/termuxplus/home";
    private static final String PREFIX_AGENTS_SOURCE_FILE_PATH = PREFIX_HOME_SOURCE_DIR_PATH + "/AGENTS.md";
    private static final String PREFIX_SKILLS_SOURCE_DIR_PATH = PREFIX_HOME_SOURCE_DIR_PATH + "/.codex/skills";

    private static final String ASSET_AGENTS_SOURCE_FILE_PATH = "termuxplus/home/AGENTS.md";
    private static final String ASSET_SKILLS_SOURCE_DIR_PATH = "termuxplus/codex-skills";

    private static final String HOME_AGENTS_TARGET_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/AGENTS.md";
    private static final String HOME_SKILLS_TARGET_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.codex/skills";

    private static final int PRIVATE_DIRECTORY_MODE = 0700;
    private static final int PRIVATE_FILE_MODE = 0600;

    private TermuxPlusHomeInstaller() {}

    static void syncBundledHomeFilesIfAvailable(Context context) {
        try {
            installAgentsFile(context);
            installSkills(context);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to sync TermuxPlus home files", e);
        }
    }

    private static void installAgentsFile(Context context) throws Exception {
        copyPrefixFileIfExists(
            "TermuxPlus home AGENTS.md",
            PREFIX_AGENTS_SOURCE_FILE_PATH,
            HOME_AGENTS_TARGET_FILE_PATH
        );
        copyAssetFileIfExists(context, ASSET_AGENTS_SOURCE_FILE_PATH, new File(HOME_AGENTS_TARGET_FILE_PATH));
    }

    private static void installSkills(Context context) throws Exception {
        copyPrefixSkillsIfExists();
        copyAssetDirectoryContentsIfExists(context, ASSET_SKILLS_SOURCE_DIR_PATH, new File(HOME_SKILLS_TARGET_DIR_PATH));
    }

    private static boolean copyPrefixFileIfExists(String label, String sourcePath, String targetPath) throws Exception {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.isFile()) {
            return false;
        }

        ensureDirectory(new File(targetPath).getParentFile());
        // AGENTS.md / SKILL.md are treated as install-managed instruction files,
        // not user-owned data. Always overwrite so APK upgrades pick up new
        // capability documentation. Users who want a custom file should put it
        // somewhere else under $HOME (it won't get clobbered there).
        Error error = FileUtils.copyRegularFile(label, sourcePath, targetPath, true);
        if (error != null) {
            throw new RuntimeException(Error.getMinimalErrorString(error));
        }
        Os.chmod(targetPath, PRIVATE_FILE_MODE);
        return true;
    }

    private static void copyPrefixSkillsIfExists() throws Exception {
        File sourceDirectory = new File(PREFIX_SKILLS_SOURCE_DIR_PATH);
        if (!sourceDirectory.isDirectory()) {
            return;
        }

        File targetDirectory = new File(HOME_SKILLS_TARGET_DIR_PATH);
        ensureDirectory(targetDirectory);

        File[] skillFiles = sourceDirectory.listFiles();
        if (skillFiles == null) {
            return;
        }

        // Same rationale as copyPrefixFileIfExists: skill instructions are
        // install-managed; always overwrite so APK upgrades push new docs.
        for (File sourceSkillFile : skillFiles) {
            File targetSkillFile = new File(targetDirectory, sourceSkillFile.getName());
            Error error;
            if (sourceSkillFile.isDirectory()) {
                error = FileUtils.copyDirectoryFile("TermuxPlus home skill " + sourceSkillFile.getName(),
                    sourceSkillFile.getAbsolutePath(), targetSkillFile.getAbsolutePath(), true);
            } else if (sourceSkillFile.isFile()) {
                error = FileUtils.copyRegularFile("TermuxPlus home skill file " + sourceSkillFile.getName(),
                    sourceSkillFile.getAbsolutePath(), targetSkillFile.getAbsolutePath(), true);
            } else {
                continue;
            }
            if (error != null) {
                throw new RuntimeException(Error.getMinimalErrorString(error));
            }
        }
    }

    private static boolean copyAssetFileIfExists(Context context, String sourceAssetPath, File targetFile) throws Exception {
        try {
            copyAssetFile(context.getAssets(), sourceAssetPath, targetFile);
            return true;
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean copyAssetDirectoryContentsIfExists(Context context, String sourceAssetPath, File targetDirectory) throws Exception {
        AssetManager assetManager = context.getAssets();
        String[] children = assetManager.list(sourceAssetPath);
        if (children == null || children.length == 0) {
            return false;
        }

        ensureDirectory(targetDirectory);
        for (String child : children) {
            copyAssetPath(assetManager, sourceAssetPath + "/" + child, new File(targetDirectory, child));
        }
        return true;
    }

    private static void copyAssetPath(AssetManager assetManager, String sourceAssetPath, File targetFile) throws Exception {
        String[] children = assetManager.list(sourceAssetPath);
        if (children != null && children.length > 0) {
            ensureDirectory(targetFile);
            for (String child : children) {
                copyAssetPath(assetManager, sourceAssetPath + "/" + child, new File(targetFile, child));
            }
            return;
        }

        copyAssetFile(assetManager, sourceAssetPath, targetFile);
    }

    private static void copyAssetFile(AssetManager assetManager, String sourceAssetPath, File targetFile) throws Exception {
        ensureDirectory(targetFile.getParentFile());
        File tempFile = new File(targetFile.getParentFile(), "." + targetFile.getName() + ".tmp");

        try (InputStream inputStream = assetManager.open(sourceAssetPath);
             FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }

        Os.chmod(tempFile.getAbsolutePath(), PRIVATE_FILE_MODE);
        Os.rename(tempFile.getAbsolutePath(), targetFile.getAbsolutePath());
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (directory == null) {
            return;
        }

        Error error = FileUtils.createDirectoryFile(directory.getAbsolutePath());
        if (error != null) {
            throw new RuntimeException(Error.getMinimalErrorString(error));
        }
        Os.chmod(directory.getAbsolutePath(), PRIVATE_DIRECTORY_MODE);
    }
}
