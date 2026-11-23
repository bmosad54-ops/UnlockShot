/*
 * UnlockShot - Xposed module (Vector / libxposed API 101-102)
 *
 * Based on DisableFlagSecure / "Enable Screenshot" by the LSPosed team
 * (Wang Han, tehcneko and contributors), https://github.com/LSPosed/DisableFlagSecure
 * Original and this derivative are licensed under GNU GPL v3.0.
 *
 * Changes vs upstream:
 *  - Renamed package/entry point.
 *  - Every hook attempt is logged as OK/FAIL to the Xposed log, so the
 *    Vector Logs tab shows exactly which hooks applied on this device/ROM.
 *  - ScreenCapture and ScreenshotHardwareBuffer hooks resolve their class
 *    and field names by probing (newest layout first) instead of only
 *    branching on SDK version, so newer/renamed system layouts fall back
 *    cleanly instead of silently doing nothing.
 */
package com.abdul.unlockshot;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.github.libxposed.api.XposedModule;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class UnlockShot extends XposedModule {
    private static final String TAG = "UnlockShot";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String OPLUS_APPPLATFORM = "com.oplus.appplatform";
    private static final String OPLUS_SCREENSHOT = "com.oplus.screenshot";
    private static final String FLYME_SYSTEMUIEX = "com.flyme.systemuiex";
    private static final String MIUI_SCREENSHOT = "com.miui.screenshot";
    private static final String OPLUS_SCREENRECORDER = "com.oplus.screenrecorder";

    private static XposedModule module;
    private Pair<String, ClassLoader> param;
    private final Set<String> hookedIds = new HashSet<>();

    private interface HookTask {
        void run() throws Throwable;
    }

    private void tryHook(String name, HookTask task) {
        try {
            task.run();
            log(Log.INFO, TAG, "OK: " + name);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "FAIL: " + name + " -> " + t);
        }
    }

    private static Class<?> loadFirst(ClassLoader cl, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String n : names) {
            try {
                return cl.loadClass(n);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException("none of " + Arrays.toString(names));
    }

    // true when running on Android 16 QPR1 (BAKLAVA_1) or newer; reflective so
    // it never breaks on ROMs where the constant is missing.
    private static boolean isBaklava1Plus() {
        try {
            Class<?> full = Class.forName("android.os.Build$VERSION_CODES_FULL");
            Field f = full.getField("BAKLAVA_1");
            int b1 = f.getInt(null);
            return Build.VERSION.SDK_INT_FULL >= b1;
        } catch (Throwable t) {
            return Build.VERSION.SDK_INT > 36;
        }
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        module = this;
        log(Log.INFO, TAG, "module loaded, api=" + getApiVersion()
                + " sdk=" + Build.VERSION.SDK_INT + " full=" + Build.VERSION.SDK_INT_FULL
                + " device=" + Build.DEVICE + " " + Build.FINGERPRINT);
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = Pair.create("system", classLoader);
        log(Log.INFO, TAG, "system server starting, sdk=" + Build.VERSION.SDK_INT);
        tryHook("deoptimizeSystemServer", () -> deoptimizeSystemServer(classLoader));
        hookSystemServer(classLoader);
    }

    private void hookSystemServer(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT >= 35) {
            // Screen record detection (V+)
            tryHook("WindowManagerService.registerScreenRecordingCallback",
                    () -> hookWindowManagerService(classLoader));
        }

        if (Build.VERSION.SDK_INT >= 34) {
            // Screenshot detection (U+)
            tryHook("ActivityTaskManagerService.registerScreenCaptureObserver",
                    () -> hookActivityTaskManagerService(classLoader));

            // Xiaomi HyperOS
            tryHook("HyperOS.notAllowCaptureDisplay", () -> {
                try {
                    hookHyperOS(classLoader);
                } catch (ClassNotFoundException notHyperOs) {
                    // expected on non-Xiaomi
                }
            });
        }

        // ScreenCapture in WindowManagerService
        tryHook("ScreenCapture.nativeCapture*", () -> hookScreenCapture(classLoader));

        // WifiDisplay / OverlayDisplay / VirtualDisplay
        tryHook("DisplayControl.createDisplay", () -> hookDisplayControl(classLoader));

        // VirtualDisplay with MediaProjection
        tryHook("VirtualDisplayAdapter.createVirtualDisplayLocked",
                () -> hookVirtualDisplayAdapter(classLoader));

        // OneUI ScreenshotHardwareBuffer
        tryHook("ScreenshotHardwareBuffer.containsSecureLayers",
                () -> hookScreenshotHardwareBuffer(classLoader));

        // OneUI WmScreenshotController
        tryHook("OneUI.WmScreenshotController.canBeScreenshotTarget", () -> {
            try {
                hookOneUI(classLoader);
            } catch (ClassNotFoundException notOneUi) {
                // expected on non-Samsung
            }
        });

        // secureLocked flag
        tryHook("WindowState.isSecureLocked", () -> hookWindowState(classLoader));

        // oplus dumpsys longshot
        tryHook("Oplus.longshot", () -> {
            try {
                hookOplus(classLoader);
            } catch (ClassNotFoundException notOplus) {
                // expected on non-Oplus
            }
        });
    }

    @SuppressLint("PrivateApi")
    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;

        var classLoader = param.getClassLoader();
        var packageName = param.getPackageName();
        this.param = Pair.create(packageName, classLoader);
        hookPackage(packageName, classLoader);
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        switch (packageName) {
            case OPLUS_SCREENSHOT:
                if (Build.VERSION.SDK_INT >= 35) {
                    tryHook("Oplus.ScreenCapture.setUid", () -> {
                        try {
                            hookOplusScreenCapture(classLoader);
                        } catch (ClassNotFoundException e) {
                            // not this Oplus version
                        }
                    });
                }
                tryHook("Oplus.ScreenshotHardwareBuffer", () -> {
                    try {
                        hookOplusHardwareBuffer(classLoader);
                    } catch (ClassNotFoundException e) {
                        // not present
                    }
                });
                // fall through
            case FLYME_SYSTEMUIEX:
            case OPLUS_APPPLATFORM:
                tryHook("app.ScreenshotHardwareBuffer", () -> {
                    try {
                        hookScreenshotHardwareBuffer(classLoader);
                    } catch (ClassNotFoundException e) {
                        // not present
                    }
                });
                // fall through
            case SYSTEMUI:
            case MIUI_SCREENSHOT:
                if (OPLUS_APPPLATFORM.equals(packageName) || OPLUS_SCREENSHOT.equals(packageName) ||
                        Build.VERSION.SDK_INT < 34) {
                    tryHook("app.ScreenCapture", () -> hookScreenCapture(classLoader));
                }
                break;
            case OPLUS_SCREENRECORDER:
                tryHook("Oplus.recorder.ScreenshotHardwareBuffer", () -> {
                    try {
                        hookOplusHardwareBuffer(classLoader);
                    } catch (ClassNotFoundException e) {
                        // not present
                    }
                });
                tryHook("Oplus.InfoMonitor.registerSfEvent", () -> {
                    try {
                        hookOplusInfoMonitor(classLoader);
                    } catch (ClassNotFoundException e) {
                        // not present
                    }
                });
                break;
            default:
                try {
                    hookOnResume();
                } catch (Throwable ignored) {
                }
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        param.setSavedInstanceState(this.param);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        var isSystemServer = param.isSystemServer();
        if (param.getSavedInstanceState() instanceof Pair<?, ?> pair
                && pair.first instanceof String packageName
                && pair.second instanceof ClassLoader classLoader) {
            this.param = Pair.create(packageName, classLoader);
            try {
                if (isSystemServer) {
                    hookSystemServer(classLoader);
                } else {
                    hookPackage(packageName, classLoader);
                }
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Hot reload failed", tr);
            }
        }
        param.getOldHookHandles().forEach(h -> {
            if (!hookedIds.contains(h.getId())) {
                h.unhook();
            }
        });
    }

    private void deoptimizeSystemServer(ClassLoader classLoader) throws ClassNotFoundException {
        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
                "createSurfaceLocked");

        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowManagerService"),
                "relayoutWindow");

        for (int i = 0; i < 20; i++) {
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda" + i);
                if (BiConsumer.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "accept");
                }
            } catch (ClassNotFoundException ignored) {
            }
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.DisplayContent$" + i);
                if (BiPredicate.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "test");
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void deoptimizeMethods(Class<?> clazz, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(this::deoptimize);
    }

    private void hookWindowState(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowStateClazz = classLoader.loadClass("com.android.server.wm.WindowState");
        var systemServerCl = windowStateClazz.getClassLoader();
        var isSecureLockedMethod = windowStateClazz.getDeclaredMethod("isSecureLocked");
        hookE(isSecureLockedMethod).intercept(chain -> {
            if (Build.VERSION.SDK_INT >= 34) {
                var walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
                var match = walker.walk(frames -> frames
                        .anyMatch(frame -> frame.getDeclaringClass() != null &&
                                frame.getDeclaringClass().getClassLoader() == systemServerCl &&
                                (frame.getMethodName().equals("setInitialSurfaceControlProperties") ||
                                        frame.getMethodName().equals("createSurfaceLocked"))));
                if (match) return chain.proceed();
            } else {
                var stackTrace = new Throwable().getStackTrace();
                for (var frame : stackTrace) {
                    var name = frame.getMethodName();
                    try {
                        if ((name.equals("setInitialSurfaceControlProperties") ||
                                name.equals("createSurfaceLocked")) &&
                                classLoader.loadClass(frame.getClassName()).getClassLoader() == systemServerCl) {
                            return chain.proceed();
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            return false;
        });
    }

    private void hookScreenCapture(ClassLoader classLoader) throws ClassNotFoundException, NoSuchFieldException {
        // Probe newest layout first instead of trusting SDK branches.
        Class<?> screenCaptureClazz;
        try {
            screenCaptureClazz = classLoader.loadClass("android.window.ScreenCaptureInternal");
        } catch (ClassNotFoundException e) {
            try {
                screenCaptureClazz = classLoader.loadClass("android.window.ScreenCapture");
            } catch (ClassNotFoundException e2) {
                screenCaptureClazz = android.view.SurfaceControl.class;
            }
        }
        Class<?> captureArgsClazz;
        if (screenCaptureClazz == android.view.SurfaceControl.class) {
            captureArgsClazz = classLoader.loadClass("android.view.SurfaceControl$CaptureArgs");
        } else {
            captureArgsClazz = classLoader.loadClass(screenCaptureClazz.getName() + "$CaptureArgs");
        }
        Field secureField = null;
        boolean intPolicy = false;
        try {
            secureField = captureArgsClazz.getDeclaredField("mSecureContentPolicy");
            intPolicy = true;
        } catch (NoSuchFieldException e) {
            secureField = captureArgsClazz.getDeclaredField("mCaptureSecureLayers");
        }
        secureField.setAccessible(true);
        final Field field = secureField;
        final boolean isInt = intPolicy;
        log(Log.INFO, TAG, "ScreenCapture hook target: " + screenCaptureClazz.getName()
                + " field=" + field.getName());
        Hooker hooker = chain -> {
            var captureArgs = chain.getArg(0);
            try {
                if (isInt) {
                    field.set(captureArgs, 1);
                } else {
                    field.set(captureArgs, true);
                }
            } catch (IllegalAccessException t) {
                module.log(Log.ERROR, TAG, "ScreenCaptureHooker failed", t);
            }
            return chain.proceed();
        };
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureDisplay");
        hookMethods(screenCaptureClazz, hooker, "nativeCaptureLayers");
    }

    private void hookDisplayControl(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var displayControlClazz = Build.VERSION.SDK_INT >= 34 ?
                classLoader.loadClass("com.android.server.display.DisplayControl") :
                android.view.SurfaceControl.class;
        var systemServerCl = displayControlClazz.getClassLoader();
        var method = displayControlClazz.getDeclaredMethod(
                Build.VERSION.SDK_INT >= 35 ?
                        "createVirtualDisplay" :
                        "createDisplay", String.class, boolean.class);
        hookE(method).intercept(chain -> {
            if (Build.VERSION.SDK_INT < 34) {
                var stackTrace = new Throwable().getStackTrace();
                for (var frame : stackTrace) {
                    var name = frame.getMethodName();
                    try {
                        if (name.equals("createVirtualDisplayLocked") &&
                                classLoader.loadClass(frame.getClassName()).getClassLoader() == systemServerCl) {
                            return chain.proceed();
                        }
                    } catch (ClassNotFoundException ignored) {
                    }
                }
            }
            var args = chain.getArgs().toArray();
            args[1] = true;
            return chain.proceed(args);
        });
    }

    private void hookVirtualDisplayAdapter(ClassLoader classLoader) throws ClassNotFoundException {
        var displayControlClazz = classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter");
        hookMethods(displayControlClazz, chain -> {
            var caller = (int) chain.getArg(2);
            if (caller >= 10000 && chain.getArg(1) == null) {
                // not os and not media projection
                return chain.proceed();
            }
            for (int i = 3; i < chain.getArgs().size(); i++) {
                var arg = chain.getArg(i);
                if (arg instanceof Integer flags) {
                    flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
                    var args = chain.getArgs().toArray();
                    args[i] = flags;
                    return chain.proceed(args);
                }
            }
            module.log(Log.WARN, TAG, "flag not found in CreateVirtualDisplayLockedHooker");
            return chain.proceed();
        }, "createVirtualDisplayLocked");
    }

    private void hookActivityTaskManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService");
        var iBinderClazz = classLoader.loadClass("android.os.IBinder");
        var iScreenCaptureObserverClazz = classLoader.loadClass("android.app.IScreenCaptureObserver");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("registerScreenCaptureObserver", iBinderClazz, iScreenCaptureObserverClazz);
        hookE(method).intercept(chain -> null);
    }

    private void hookWindowManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
        var iScreenRecordingCallbackClazz = classLoader.loadClass("android.window.IScreenRecordingCallback");
        var method = windowManagerServiceClazz.getDeclaredMethod("registerScreenRecordingCallback", iScreenRecordingCallbackClazz);
        hookE(method).intercept(chain -> false);
    }

    private void hookHyperOS(ClassLoader classLoader) throws ClassNotFoundException {
        var windowManagerServiceImplClazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
        hookMethods(windowManagerServiceImplClazz, chain -> false, "notAllowCaptureDisplay");
    }

    private void hookScreenshotHardwareBuffer(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var screenshotHardwareBufferClazz = loadFirst(classLoader,
                "android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer",
                "android.window.ScreenCapture$ScreenshotHardwareBuffer",
                "android.view.SurfaceControl$ScreenshotHardwareBuffer");
        var method = screenshotHardwareBufferClazz.getDeclaredMethod("containsSecureLayers");
        hookE(method).intercept(chain -> false);
    }

    private void hookOplusScreenCapture(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var oplusScreenCaptureClazz = classLoader.loadClass("com.oplus.screenshot.OplusScreenCapture$CaptureArgs$Builder");
        var method = oplusScreenCaptureClazz.getDeclaredMethod("setUid", long.class);
        hookE(method).intercept(chain -> {
            var args = chain.getArgs().toArray();
            args[0] = -1;
            return chain.proceed(args);
        });
    }

    private void hookOplus(ClassLoader classLoader) throws ClassNotFoundException {
        var longshotMainClazz = classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow");
        hookMethods(longshotMainClazz, chain -> false, "hasSecure");
    }

    private void hookOneUI(ClassLoader classLoader) throws ClassNotFoundException {
        var wmScreenshotControllerClazz = classLoader.loadClass("com.android.server.wm.WmScreenshotController");
        hookMethods(wmScreenshotControllerClazz, chain -> true, "canBeScreenshotTarget");
    }

    private void hookMethods(Class<?> clazz, Hooker hooker, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(method -> hookE(method).intercept(hooker));
    }

    private HookBuilder hookE(Executable executable) {
        var builder = hook(executable);

        if (getApiVersion() >= 102) {
            var id = executable.toGenericString();
            builder.setId(id);
            hookedIds.add(id);
        }

        return builder;
    }

    private void hookOplusHardwareBuffer(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var clazz = classLoader.loadClass("com.oplus.screenshot.OplusScreenCapture$ScreenshotHardwareBuffer");
        var method = clazz.getDeclaredMethod("containsSecureLayers");
        hookE(method).intercept(chain -> false);
    }

    private void hookOplusInfoMonitor(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var oplusInfoMonitorClazz = classLoader.loadClass("com.oplus.display.OplusInfoMonitor");
        var listenerClazz = classLoader.loadClass("com.oplus.display.OplusInfoMonitor$OplusInfoMonitorListener");
        var method = oplusInfoMonitorClazz.getDeclaredMethod("registerSfEvent", listenerClazz, int.class, String.class);
        hookE(method).intercept(chain -> {
            var listener = chain.getArg(0);
            var tag = chain.getArg(2);
            if ("com.oplus.screenrecordersecure".equals(tag)
                    && listener != null
                    && listener.getClass().getName().startsWith("com.oplus.screenrecorder.")) {
                return null;
            }
            return chain.proceed();
        });
    }

    private void hookOnResume() throws NoSuchMethodException {
        var method = Activity.class.getDeclaredMethod("onResume");
        hookE(method).intercept(chain -> {
            var activity = (Activity) chain.getThisObject();
            new AlertDialog.Builder(activity)
                    .setTitle("UnlockShot")
                    .setMessage("Incorrect module usage, remove this app from scope.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> System.exit(0))
                    .show();
            return chain.proceed();
        });
    }
                  }
