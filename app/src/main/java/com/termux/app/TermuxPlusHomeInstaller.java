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
    private static final String PREFIX_SCRIPTS_SOURCE_DIR_PATH = PREFIX_HOME_SOURCE_DIR_PATH + "/.termuxplus/scripts";
    private static final String PREFIX_GITCONFIG_SOURCE_FILE_PATH = PREFIX_HOME_SOURCE_DIR_PATH + "/.gitconfig";
    private static final String PREFIX_GIT_IGNORE_SOURCE_FILE_PATH = PREFIX_HOME_SOURCE_DIR_PATH + "/.config/git/ignore";

    private static final String ASSET_AGENTS_SOURCE_FILE_PATH = "termuxplus/home/AGENTS.md";
    private static final String ASSET_SKILLS_SOURCE_DIR_PATH = "termuxplus/codex-skills";
    private static final String ASSET_SCRIPTS_SOURCE_DIR_PATH = "termuxplus/home-scripts";
    // aapt ignores asset paths whose components start with ".", so these are
    // staged under non-dotted names in the APK and renamed when copied to $HOME.
    private static final String ASSET_GITCONFIG_SOURCE_FILE_PATH = "termuxplus/home/gitconfig";
    private static final String ASSET_GIT_IGNORE_SOURCE_FILE_PATH = "termuxplus/home/config/git/ignore";

    private static final String HOME_AGENTS_TARGET_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/AGENTS.md";
    private static final String HOME_SKILLS_TARGET_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.codex/skills";
    private static final String HOME_TERMUXPLUS_TARGET_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termuxplus";
    private static final String HOME_SCRIPTS_TARGET_DIR_PATH = HOME_TERMUXPLUS_TARGET_DIR_PATH + "/scripts";
    private static final String HOME_GITCONFIG_TARGET_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.gitconfig";
    private static final String HOME_GIT_IGNORE_TARGET_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.config/git/ignore";

    private static final int PRIVATE_DIRECTORY_MODE = 0700;
    private static final int PRIVATE_FILE_MODE = 0600;
    private static final int EXECUTABLE_FILE_MODE = 0700;

    private static final HomeTemplateEntry[] HOME_TEMPLATE_ENTRIES = new HomeTemplateEntry[] {
        HomeTemplateEntry.file(
            "TermuxPlus home AGENTS.md",
            PREFIX_AGENTS_SOURCE_FILE_PATH,
            ASSET_AGENTS_SOURCE_FILE_PATH,
            HOME_AGENTS_TARGET_FILE_PATH,
            PRIVATE_FILE_MODE
        ),
        HomeTemplateEntry.directory(
            "TermuxPlus home Codex skill",
            PREFIX_SKILLS_SOURCE_DIR_PATH,
            ASSET_SKILLS_SOURCE_DIR_PATH,
            HOME_SKILLS_TARGET_DIR_PATH,
            PRIVATE_FILE_MODE
        ),
        HomeTemplateEntry.directory(
            "TermuxPlus home script",
            PREFIX_SCRIPTS_SOURCE_DIR_PATH,
            ASSET_SCRIPTS_SOURCE_DIR_PATH,
            HOME_SCRIPTS_TARGET_DIR_PATH,
            EXECUTABLE_FILE_MODE
        ),
        // User-owned dotfiles: seed them on first boot so a fresh install gets
        // gh credential helper and the agent-friendly gitignore, but never
        // overwrite a user's existing file on subsequent APK upgrades.
        HomeTemplateEntry.preservedFile(
            "TermuxPlus home .gitconfig",
            PREFIX_GITCONFIG_SOURCE_FILE_PATH,
            ASSET_GITCONFIG_SOURCE_FILE_PATH,
            HOME_GITCONFIG_TARGET_FILE_PATH,
            PRIVATE_FILE_MODE
        ),
        HomeTemplateEntry.preservedFile(
            "TermuxPlus home .config/git/ignore",
            PREFIX_GIT_IGNORE_SOURCE_FILE_PATH,
            ASSET_GIT_IGNORE_SOURCE_FILE_PATH,
            HOME_GIT_IGNORE_TARGET_FILE_PATH,
            PRIVATE_FILE_MODE
        )
    };

    private TermuxPlusHomeInstaller() {}

    static void syncBundledHomeFilesIfAvailable(Context context) {
        try {
            for (HomeTemplateEntry entry : HOME_TEMPLATE_ENTRIES) {
                syncHomeTemplateEntry(context, entry);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to sync TermuxPlus home files", e);
        }
    }

    private static void syncHomeTemplateEntry(Context context, HomeTemplateEntry entry) throws Exception {
        File targetFile = new File(entry.homeTargetPath);
        if (entry.isDirectory) {
            copyPrefixDirectoryContentsIfExists(entry.label, new File(entry.prefixSourcePath), targetFile, entry.fileMode);
            copyAssetDirectoryContentsIfExists(context, entry.assetSourcePath, targetFile, entry.fileMode);
            return;
        }

        if (entry.preserveExisting && targetFile.exists()) {
            // User already owns this file (e.g. they added [user] to ~/.gitconfig);
            // do not clobber it on APK upgrade.
            return;
        }

        copyPrefixFileIfExists(entry.label, entry.prefixSourcePath, targetFile, entry.fileMode);
        copyAssetFileIfExists(context, entry.assetSourcePath, targetFile, entry.fileMode);
    }

    private static boolean copyPrefixFileIfExists(String label, String sourcePath, File targetFile, int fileMode) throws Exception {
        File sourceFile = new File(sourcePath);
        if (!sourceFile.isFile()) {
            return false;
        }

        ensureDirectory(targetFile.getParentFile());
        // Home template entries are treated as install-managed instruction/tool files,
        // not user-owned data. Always overwrite so APK upgrades pick up new
        // capability documentation and scripts. Users who want custom files should put them
        // somewhere else under $HOME (it won't get clobbered there).
        Error error = FileUtils.copyRegularFile(label, sourcePath, targetFile.getAbsolutePath(), true);
        if (error != null) {
            throw new RuntimeException(Error.getMinimalErrorString(error));
        }
        Os.chmod(targetFile.getAbsolutePath(), fileMode);
        return true;
    }

    private static boolean copyAssetFileIfExists(Context context, String sourceAssetPath, File targetFile,
                                                int fileMode) throws Exception {
        try {
            copyAssetFile(context.getAssets(), sourceAssetPath, targetFile, fileMode);
            return true;
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean copyAssetDirectoryContentsIfExists(Context context, String sourceAssetPath, File targetDirectory,
                                                             int fileMode) throws Exception {
        AssetManager assetManager = context.getAssets();
        String[] children = assetManager.list(sourceAssetPath);
        if (children == null || children.length == 0) {
            return false;
        }

        ensureDirectory(targetDirectory);
        for (String child : children) {
            copyAssetPath(assetManager, sourceAssetPath + "/" + child, new File(targetDirectory, child), fileMode);
        }
        return true;
    }

    private static void copyAssetPath(AssetManager assetManager, String sourceAssetPath, File targetFile,
                                      int fileMode) throws Exception {
        String[] children = assetManager.list(sourceAssetPath);
        if (children != null && children.length > 0) {
            ensureDirectory(targetFile);
            for (String child : children) {
                copyAssetPath(assetManager, sourceAssetPath + "/" + child, new File(targetFile, child), fileMode);
            }
            return;
        }

        copyAssetFile(assetManager, sourceAssetPath, targetFile, fileMode);
    }

    private static void copyAssetFile(AssetManager assetManager, String sourceAssetPath, File targetFile,
                                      int fileMode) throws Exception {
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

        Os.chmod(tempFile.getAbsolutePath(), fileMode);
        Os.rename(tempFile.getAbsolutePath(), targetFile.getAbsolutePath());
    }

    private static boolean copyPrefixDirectoryContentsIfExists(String label, File sourceDirectory, File targetDirectory,
                                                              int fileMode) throws Exception {
        if (!sourceDirectory.isDirectory()) {
            return false;
        }

        ensureDirectory(targetDirectory);
        File[] sourceFiles = sourceDirectory.listFiles();
        if (sourceFiles == null) {
            return true;
        }

        for (File sourceFile : sourceFiles) {
            copyPrefixPath(label + " " + sourceFile.getName(), sourceFile,
                new File(targetDirectory, sourceFile.getName()), fileMode);
        }
        return true;
    }

    private static void copyPrefixPath(String label, File sourceFile, File targetFile, int fileMode) throws Exception {
        if (sourceFile.isDirectory()) {
            ensureDirectory(targetFile);
            File[] children = sourceFile.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                copyPrefixPath(label + "/" + child.getName(), child, new File(targetFile, child.getName()), fileMode);
            }
            return;
        }

        if (!sourceFile.isFile()) {
            return;
        }

        ensureDirectory(targetFile.getParentFile());
        Error error = FileUtils.copyRegularFile(label, sourceFile.getAbsolutePath(), targetFile.getAbsolutePath(), true);
        if (error != null) {
            throw new RuntimeException(Error.getMinimalErrorString(error));
        }
        Os.chmod(targetFile.getAbsolutePath(), fileMode);
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

    private static final class HomeTemplateEntry {
        final String label;
        final String prefixSourcePath;
        final String assetSourcePath;
        final String homeTargetPath;
        final boolean isDirectory;
        final int fileMode;
        final boolean preserveExisting;

        private HomeTemplateEntry(String label, String prefixSourcePath, String assetSourcePath,
                                  String homeTargetPath, boolean isDirectory, int fileMode,
                                  boolean preserveExisting) {
            this.label = label;
            this.prefixSourcePath = prefixSourcePath;
            this.assetSourcePath = assetSourcePath;
            this.homeTargetPath = homeTargetPath;
            this.isDirectory = isDirectory;
            this.fileMode = fileMode;
            this.preserveExisting = preserveExisting;
        }

        static HomeTemplateEntry file(String label, String prefixSourcePath, String assetSourcePath,
                                      String homeTargetPath, int fileMode) {
            return new HomeTemplateEntry(label, prefixSourcePath, assetSourcePath, homeTargetPath, false, fileMode, false);
        }

        static HomeTemplateEntry preservedFile(String label, String prefixSourcePath, String assetSourcePath,
                                               String homeTargetPath, int fileMode) {
            return new HomeTemplateEntry(label, prefixSourcePath, assetSourcePath, homeTargetPath, false, fileMode, true);
        }

        static HomeTemplateEntry directory(String label, String prefixSourcePath, String assetSourcePath,
                                           String homeTargetPath, int fileMode) {
            return new HomeTemplateEntry(label, prefixSourcePath, assetSourcePath, homeTargetPath, true, fileMode, false);
        }
    }
}
