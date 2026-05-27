package com.termux.app.terminal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback bridge for terminal-first Android launcher commands.
 *
 * Shell commands cannot safely call Android framework tools like am/pm from the
 * Termux app SELinux context, so tp-home talks to this in-process bridge.
 */
public final class TermuxTerminalHomeBridge {

    public static final String URL_SCHEME = "termuxplus";
    public static final String SHORT_URL_SCHEME = "tp";

    private static final String LOG_TAG = "TermuxTerminalHomeBridge";
    private static final int BRIDGE_PORT = 8077;
    private static final int BRIDGE_BACKLOG = 4;
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int MAIN_THREAD_TIMEOUT_SECONDS = 10;
    private static final int PRIVATE_DIRECTORY_MODE = 0700;
    private static final int PRIVATE_FILE_MODE = 0600;

    private static final String LAUNCHER_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termuxplus/launcher";
    private static final String TOKEN_FILE_PATH = LAUNCHER_DIR_PATH + "/bridge-token";
    private static final String STATE_FILE_PATH = LAUNCHER_DIR_PATH + "/bridge.json";
    private static final String APPS_CACHE_FILE_PATH = LAUNCHER_DIR_PATH + "/apps.json";
    private static final String HOME_ALIAS_CLASS_NAME = "com.termux.app.TermuxTerminalHomeActivity";

    private final TermuxService mService;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private ServerSocket mServerSocket;
    private Thread mServerThread;
    private volatile boolean mRunning;
    private String mToken;

    public TermuxTerminalHomeBridge(@NonNull TermuxService service) {
        mService = service;
    }

    public synchronized void start() {
        if (mRunning) return;

        try {
            ensureLauncherDirectory();
            mToken = readOrCreateToken();
            writeBridgeState();
            syncAppsCache(mService);

            mServerSocket = new ServerSocket();
            mServerSocket.setReuseAddress(true);
            mServerSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), BRIDGE_PORT), BRIDGE_BACKLOG);

            mRunning = true;
            mServerThread = new Thread(this::serve, "termux-terminal-home-bridge");
            mServerThread.setDaemon(true);
            mServerThread.start();
            Logger.logInfo(LOG_TAG, "Started terminal home bridge on 127.0.0.1:" + BRIDGE_PORT);
        } catch (Exception e) {
            closeServerSocket();
            mRunning = false;
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start terminal home bridge", e);
        }
    }

    public synchronized void stop() {
        mRunning = false;
        closeServerSocket();
    }

    private void serve() {
        while (mRunning) {
            try {
                Socket socket = mServerSocket.accept();
                socket.setSoTimeout(CLIENT_TIMEOUT_MS);
                handleSocket(socket);
            } catch (Exception e) {
                if (mRunning)
                    Logger.logStackTraceWithMessage(LOG_TAG, "Terminal home bridge client failure", e);
            }
        }
    }

    private void handleSocket(Socket socket) {
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStreamWriter writer = new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)) {
            String requestLine = reader.readLine();
            JSONObject response = requestLine == null ? error("empty request") : dispatch(new JSONObject(requestLine));
            writer.write(response.toString());
            writer.write("\n");
            writer.flush();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to handle terminal home bridge request", e);
        }
    }

    private JSONObject dispatch(JSONObject request) throws Exception {
        String token = request.optString("token", "");
        if (!TextUtils.equals(token, mToken))
            return error("invalid token");

        return runOnMain(() -> dispatchOnMain(request));
    }

    private JSONObject dispatchOnMain(JSONObject request) throws Exception {
        String action = request.optString("action", "");
        switch (action) {
            case "ping":
                return ok().put("port", BRIDGE_PORT);
            case "apps":
                return listApps(request.optString("query", ""));
            case "launch":
                return launch(request);
            case "tab":
                return handleTab(request);
            case "home":
                return handleHome(request);
            case "refresh":
                syncAppsCache(mService);
                return ok().put("appsPath", APPS_CACHE_FILE_PATH);
            default:
                return error("unknown action: " + action);
        }
    }

    private JSONObject handleHome(JSONObject request) throws Exception {
        String op = request.optString("op", "status");
        switch (op) {
            case "enable":
                setHomeAliasEnabled(true);
                return homeStatus().put("message", "terminal home candidate enabled");
            case "disable":
                setHomeAliasEnabled(false);
                return homeStatus().put("message", "terminal home candidate disabled");
            case "status":
                return homeStatus();
            default:
                return error("unknown home op: " + op);
        }
    }

    private JSONObject homeStatus() throws Exception {
        return ok()
            .put("enabled", isHomeAliasEnabled())
            .put("component", HOME_ALIAS_CLASS_NAME);
    }

    private void setHomeAliasEnabled(boolean enabled) {
        int state = enabled
            ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        mService.getPackageManager().setComponentEnabledSetting(getHomeAliasComponentName(),
            state, PackageManager.DONT_KILL_APP);
    }

    private boolean isHomeAliasEnabled() {
        int state = mService.getPackageManager().getComponentEnabledSetting(getHomeAliasComponentName());
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private ComponentName getHomeAliasComponentName() {
        return new ComponentName(mService.getPackageName(), HOME_ALIAS_CLASS_NAME);
    }

    private JSONObject runOnMain(BridgeAction action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            return action.run();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        mMainHandler.post(() -> {
            try {
                result.set(action.run());
            } catch (Exception e) {
                failure.set(e);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            return error("bridge main-thread timeout");
        if (failure.get() != null)
            throw failure.get();
        return result.get();
    }

    private JSONObject listApps(String query) throws Exception {
        JSONArray appsJson = new JSONArray();
        for (AppInfo app : getLaunchableApps(mService)) {
            if (TextUtils.isEmpty(query) || app.matches(query))
                appsJson.put(app.toJson());
        }
        return ok().put("apps", appsJson).put("appsPath", APPS_CACHE_FILE_PATH);
    }

    private JSONObject launch(JSONObject request) throws Exception {
        AppInfo launched = launchApp(mService,
            nullableString(request.optString("package", null)),
            nullableString(request.optString("activity", null)),
            nullableString(request.optString("target", null)));
        return ok().put("message", "launched " + launched.label).put("app", launched.toJson());
    }

    private JSONObject handleTab(JSONObject request) throws Exception {
        String op = request.optString("op", "list");
        switch (op) {
            case "list":
                return ok().put("sessions", sessionsToJson());
            case "new":
                return createTab(request);
            case "next":
                return switchTab(true);
            case "prev":
                return switchTab(false);
            case "select":
                int index = request.optInt("index", -1);
                if (!mService.switchToTermuxSession(index))
                    return error("session not found: " + (index + 1));
                return ok().put("sessions", sessionsToJson());
            default:
                return error("unknown tab op: " + op);
        }
    }

    private JSONObject createTab(JSONObject request) throws Exception {
        String command = nullableString(request.optString("command", null));
        String name = nullableString(request.optString("name", null));
        String cwd = nullableString(request.optString("cwd", null));
        TermuxSession newSession = mService.createTermuxSessionForTerminalHome(command, name, cwd);
        if (newSession == null)
            return error("failed to create terminal tab");
        mService.switchToTermuxSession(newSession.getTerminalSession());
        return ok().put("message", "created tab").put("sessions", sessionsToJson());
    }

    private JSONObject switchTab(boolean forward) throws Exception {
        if (!mService.switchToAdjacentTermuxSession(forward))
            return error("no terminal sessions");
        return ok().put("sessions", sessionsToJson());
    }

    private JSONArray sessionsToJson() throws Exception {
        JSONArray sessions = new JSONArray();
        int currentIndex = mService.getCurrentTermuxSessionIndex();
        List<TermuxSession> termuxSessions = new ArrayList<>(mService.getTermuxSessions());
        for (int i = 0; i < termuxSessions.size(); i++) {
            TermuxSession termuxSession = termuxSessions.get(i);
            TerminalSession session = termuxSession.getTerminalSession();
            JSONObject json = new JSONObject()
                .put("index", i + 1)
                .put("current", i == currentIndex)
                .put("handle", session.mHandle)
                .put("name", nullToEmpty(session.mSessionName))
                .put("title", nullToEmpty(session.getTitle()))
                .put("cwd", nullToEmpty(session.getCwd()))
                .put("running", session.isRunning());
            if (!session.isRunning())
                json.put("exitStatus", session.getExitStatus());
            sessions.put(json);
        }
        return sessions;
    }

    public static boolean handleTerminalUrl(@NonNull TermuxActivity activity, @Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!URL_SCHEME.equalsIgnoreCase(scheme) && !SHORT_URL_SCHEME.equalsIgnoreCase(scheme))
            return false;

        try {
            String host = uri.getHost();
            if ("launch".equals(host) || "app".equals(host)) {
                String packageName = firstNonEmpty(uri.getQueryParameter("package"), firstPathSegment(uri));
                String activityName = uri.getQueryParameter("activity");
                String target = firstNonEmpty(uri.getQueryParameter("target"), packageName);
                AppInfo launched = launchApp(activity, packageName, activityName, target);
                activity.showToast("launched " + launched.label, false);
                return true;
            }

            if ("tab".equals(host)) {
                TermuxService service = activity.getTermuxService();
                if (service == null) return true;

                String op = firstNonEmpty(firstPathSegment(uri), uri.getQueryParameter("op"));
                if ("next".equals(op)) {
                    service.switchToAdjacentTermuxSession(true);
                } else if ("prev".equals(op) || "previous".equals(op)) {
                    service.switchToAdjacentTermuxSession(false);
                } else if ("select".equals(op)) {
                    int index = parseOneBasedIndex(uri.getQueryParameter("index"));
                    service.switchToTermuxSession(index);
                } else if ("new".equals(op)) {
                    TermuxSession termuxSession = service.createTermuxSessionForTerminalHome(uri.getQueryParameter("command"),
                        uri.getQueryParameter("name"), null);
                    if (termuxSession != null)
                        service.switchToTermuxSession(termuxSession.getTerminalSession());
                }
                return true;
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to handle terminal home url: " + url, e);
            activity.showToast(e.getMessage(), true);
        }

        return true;
    }

    @NonNull
    private static AppInfo launchApp(@NonNull Context context, @Nullable String packageName,
                                     @Nullable String activityName, @Nullable String target) throws Exception {
        AppInfo app = findApp(context, packageName, activityName, target);
        if (app == null)
            throw new IllegalArgumentException("app not found: " + firstNonEmpty(target, packageName));

        Intent launchIntent = null;
        if (!TextUtils.isEmpty(app.packageName))
            launchIntent = context.getPackageManager().getLaunchIntentForPackage(app.packageName);

        if (launchIntent == null) {
            launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.setComponent(new ComponentName(app.packageName, app.activityName));
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launchIntent);
        return app;
    }

    @Nullable
    private static AppInfo findApp(@NonNull Context context, @Nullable String packageName,
                                   @Nullable String activityName, @Nullable String target) {
        List<AppInfo> apps = getLaunchableApps(context);
        for (AppInfo app : apps) {
            if (!TextUtils.isEmpty(packageName) && app.packageName.equals(packageName)) {
                if (TextUtils.isEmpty(activityName) || app.activityName.equals(activityName))
                    return app;
            }
        }

        String query = firstNonEmpty(target, packageName);
        if (TextUtils.isEmpty(query)) return null;

        for (AppInfo app : apps) {
            if (app.matchesExact(query))
                return app;
        }
        for (AppInfo app : apps) {
            if (app.matches(query))
                return app;
        }
        return null;
    }

    @NonNull
    public static List<AppInfo> getLaunchableApps(@NonNull Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(launcherIntent, 0);

        List<AppInfo> apps = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) continue;
            String packageName = resolveInfo.activityInfo.packageName;
            String activityName = resolveInfo.activityInfo.name;
            CharSequence labelChars = resolveInfo.loadLabel(packageManager);
            String label = labelChars == null ? packageName : labelChars.toString();
            apps.add(new AppInfo(label, packageName, activityName));
        }

        Collections.sort(apps, (left, right) -> {
            int labelCompare = left.label.compareToIgnoreCase(right.label);
            if (labelCompare != 0) return labelCompare;
            return left.packageName.compareToIgnoreCase(right.packageName);
        });
        return apps;
    }

    public static void syncAppsCache(@NonNull Context context) {
        try {
            ensureLauncherDirectory();
            JSONArray apps = new JSONArray();
            for (AppInfo app : getLaunchableApps(context))
                apps.put(app.toJson());

            JSONObject root = new JSONObject()
                .put("version", 1)
                .put("scheme", URL_SCHEME)
                .put("apps", apps);
            writePrivateFile(APPS_CACHE_FILE_PATH, root.toString(2) + "\n");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to sync terminal home apps cache", e);
        }
    }

    private void writeBridgeState() throws Exception {
        JSONObject state = new JSONObject()
            .put("version", 1)
            .put("host", "127.0.0.1")
            .put("port", BRIDGE_PORT)
            .put("tokenPath", TOKEN_FILE_PATH)
            .put("appsPath", APPS_CACHE_FILE_PATH)
            .put("scheme", URL_SCHEME);
        writePrivateFile(STATE_FILE_PATH, state.toString(2) + "\n");
    }

    private static void ensureLauncherDirectory() throws Exception {
        File directory = new File(LAUNCHER_DIR_PATH);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("failed to create " + LAUNCHER_DIR_PATH);
        Os.chmod(directory.getAbsolutePath(), PRIVATE_DIRECTORY_MODE);
    }

    private String readOrCreateToken() throws Exception {
        File tokenFile = new File(TOKEN_FILE_PATH);
        if (tokenFile.isFile()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(tokenFile), StandardCharsets.UTF_8))) {
                String token = reader.readLine();
                if (!TextUtils.isEmpty(token))
                    return token.trim();
            }
        }

        String token = UUID.randomUUID().toString();
        writePrivateFile(TOKEN_FILE_PATH, token + "\n");
        return token;
    }

    private static void writePrivateFile(String path, String value) throws Exception {
        File targetFile = new File(path);
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IllegalStateException("failed to create " + parent.getAbsolutePath());

        File tempFile = new File(parent, "." + targetFile.getName() + ".tmp");
        try (FileOutputStream outputStream = new FileOutputStream(tempFile, false)) {
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        }
        Os.chmod(tempFile.getAbsolutePath(), PRIVATE_FILE_MODE);
        Os.rename(tempFile.getAbsolutePath(), targetFile.getAbsolutePath());
    }

    private void closeServerSocket() {
        if (mServerSocket == null) return;
        try {
            mServerSocket.close();
        } catch (Exception ignored) {
        }
        mServerSocket = null;
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private static JSONObject error(String message) throws Exception {
        return new JSONObject().put("ok", false).put("error", message);
    }

    @Nullable
    private static String nullableString(@Nullable String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Nullable
    private static String firstPathSegment(Uri uri) {
        List<String> segments = uri.getPathSegments();
        return segments.isEmpty() ? null : segments.get(0);
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String first, @Nullable String second) {
        return !TextUtils.isEmpty(first) ? first : (!TextUtils.isEmpty(second) ? second : null);
    }

    private static int parseOneBasedIndex(@Nullable String value) {
        try {
            return Integer.parseInt(value) - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private interface BridgeAction {
        JSONObject run() throws Exception;
    }

    public static final class AppInfo {
        public final String label;
        public final String packageName;
        public final String activityName;
        public final String alias;

        AppInfo(String label, String packageName, String activityName) {
            this.label = label;
            this.packageName = packageName;
            this.activityName = activityName;
            this.alias = makeAlias(label, packageName);
        }

        boolean matchesExact(String query) {
            String normalized = normalize(query);
            return normalized.equals(alias)
                || query.equals(packageName)
                || query.equals(activityName)
                || normalized.equals(normalize(label))
                || normalized.equals(normalize(packageTail(packageName)));
        }

        boolean matches(String query) {
            String normalized = normalize(query);
            return matchesExact(query)
                || normalize(label).contains(normalized)
                || normalize(packageName).contains(normalized)
                || normalize(activityName).contains(normalized);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("label", label)
                .put("alias", alias)
                .put("package", packageName)
                .put("activity", activityName)
                .put("url", URL_SCHEME + "://launch?package=" + Uri.encode(packageName));
        }

        private static String makeAlias(String label, String packageName) {
            String alias = normalize(label);
            if (TextUtils.isEmpty(alias))
                alias = normalize(packageTail(packageName));
            return alias;
        }

        private static String normalize(String value) {
            if (value == null) return "";
            String lower = value.trim().toLowerCase(Locale.US);
            StringBuilder builder = new StringBuilder();
            boolean lastWasDash = false;
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    builder.append(c);
                    lastWasDash = false;
                } else if (!lastWasDash && builder.length() > 0) {
                    builder.append('-');
                    lastWasDash = true;
                }
            }
            int length = builder.length();
            if (length > 0 && builder.charAt(length - 1) == '-')
                builder.deleteCharAt(length - 1);
            return builder.toString();
        }

        private static String packageTail(String packageName) {
            if (packageName == null) return "";
            int lastDot = packageName.lastIndexOf('.');
            return lastDot >= 0 && lastDot + 1 < packageName.length()
                ? packageName.substring(lastDot + 1)
                : packageName;
        }
    }
}
