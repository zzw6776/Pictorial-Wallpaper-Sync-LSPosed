package dev.codex.pictorialsync;

import android.app.WallpaperManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.heytap.pictorial";
    private static final String CURRENT_URI_KEY = "oplus_customize_keyguard_current_pictorial_uri";
    private static final String PICTORIAL_URI_PREFIX = "content://com.heytap.pictorial.fileProvider/";
    private static final String TAG = "PictorialWallpaperSync";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean pollerStarted = new AtomicBoolean(false);
    private final AtomicReference<String> lastSynced = new AtomicReference<>("");
    private final AtomicReference<Context> appContext = new AtomicReference<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("loaded process=" + lpparam.processName);
        hookApplicationAttach(lpparam.classLoader);
        hookSettingsSystemPutString(lpparam.classLoader);
        hookContentResolverCall(lpparam.classLoader);
        hookContentResolverUpdate(lpparam.classLoader);
    }

    private void hookApplicationAttach(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    classLoader,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            if (context == null) {
                                return;
                            }
                            Context applicationContext = context.getApplicationContext();
                            appContext.set(applicationContext != null ? applicationContext : context);
                            String value = Settings.System.getString(
                                    context.getContentResolver(),
                                    CURRENT_URI_KEY
                            );
                            syncAsync(context.getContentResolver(), value, "Application.attach");
                            startPolling(context.getContentResolver());
                        }
                    }
            );
            log("hooked Application.attach");
        } catch (Throwable t) {
            log(t);
        }
    }

    private void hookSettingsSystemPutString(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.provider.Settings$System",
                    classLoader,
                    "putString",
                    ContentResolver.class,
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = asString(param.args[1]);
                            String value = asString(param.args[2]);
                            maybeSyncFromSettingWrite((ContentResolver) param.args[0], key, value, "Settings.System.putString");
                        }
                    }
            );
            log("hooked Settings.System.putString");
        } catch (Throwable t) {
            log(t);
        }
    }

    private void hookContentResolverCall(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    ContentResolver.class,
                    "call",
                    Uri.class,
                    String.class,
                    String.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Bundle extras = (Bundle) param.args[3];
                            String key = asString(param.args[2]);
                            String value = findCandidateValue(extras);
                            maybeSyncFromSettingWrite((ContentResolver) param.thisObject, key, value, "ContentResolver.call");
                        }
                    }
            );
            log("hooked ContentResolver.call");
        } catch (Throwable t) {
            log(t);
        }
    }

    private void hookContentResolverUpdate(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    ContentResolver.class,
                    "update",
                    Uri.class,
                    ContentValues.class,
                    String.class,
                    String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            ContentValues values = (ContentValues) param.args[1];
                            String value = findCandidateValue(values);
                            maybeSyncFromSettingWrite((ContentResolver) param.thisObject, null, value, "ContentResolver.update");
                        }
                    }
            );
            log("hooked ContentResolver.update");
        } catch (Throwable t) {
            log(t);
        }
    }

    private void maybeSyncFromSettingWrite(ContentResolver resolver, String key, String value, String source) {
        if (resolver == null || value == null) {
            return;
        }
        if (!CURRENT_URI_KEY.equals(key) && !value.startsWith(PICTORIAL_URI_PREFIX)) {
            return;
        }
        syncAsync(resolver, value, source);
    }

    private void startPolling(final ContentResolver resolver) {
        if (resolver == null || !pollerStarted.compareAndSet(false, true)) {
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        String value = Settings.System.getString(resolver, CURRENT_URI_KEY);
                        syncAsync(resolver, value, "poll");
                        Thread.sleep(30000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable t) {
                        log(t);
                        try {
                            Thread.sleep(30000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }, "pictorial-wallpaper-sync");
        thread.setDaemon(true);
        thread.start();
        log("started poller interval=30000ms");
    }

    private void syncAsync(final ContentResolver resolver, final String uriString, final String source) {
        if (uriString == null || !uriString.startsWith(PICTORIAL_URI_PREFIX)) {
            return;
        }
        if (uriString.equals(lastSynced.get())) {
            return;
        }
        lastSynced.set(uriString);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Context context = getContextFromResolver(resolver);
                    if (context == null) {
                        context = appContext.get();
                    }
                    if (context == null) {
                        log("skip sync: no context source=" + source);
                        return;
                    }
                    InputStream in = resolver.openInputStream(Uri.parse(uriString));
                    if (in == null) {
                        log("skip sync: openInputStream returned null uri=" + uriString);
                        return;
                    }
                    try {
                        int id = WallpaperManager.getInstance(context).setStream(
                                in,
                                null,
                                true,
                                WallpaperManager.FLAG_SYSTEM
                        );
                        log("synced id=" + id + " source=" + source + " uri=" + uriString);
                    } finally {
                        in.close();
                    }
                } catch (Throwable t) {
                    log(t);
                }
            }
        });
    }

    private static Context getContextFromResolver(ContentResolver resolver) {
        try {
            java.lang.reflect.Field field = ContentResolver.class.getDeclaredField("mContext");
            field.setAccessible(true);
            Object context = field.get(resolver);
            return context instanceof Context ? (Context) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String findCandidateValue(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            String text = asString(value);
            if (text != null && text.startsWith(PICTORIAL_URI_PREFIX)) {
                return text;
            }
        }
        return null;
    }

    private static String findCandidateValue(ContentValues values) {
        if (values == null) {
            return null;
        }
        for (String key : values.keySet()) {
            Object value = values.get(key);
            String text = asString(value);
            if (text != null && text.startsWith(PICTORIAL_URI_PREFIX)) {
                return text;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(Throwable t) {
        XposedBridge.log(TAG + ": " + t);
        XposedBridge.log(t);
    }
}
