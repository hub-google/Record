package com.hubgoogle.record.shell;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * This context represents the *actual* ADB shell process to AudioRecord.
 * The shell program has no Application Context of its own. scrcpy and JemRec
 * use a similar ActivityThread bootstrap for their shell-launched Java process.
 * Hidden-API behavior varies by Android version / OEM and is device-tested only.
 *
 * The UID is not forged: this program MUST have been launched via the owner's
 * authenticated ADB session and be running as UID 2000.
 */
public final class ShellAudioContext extends ContextWrapper {
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static Context systemContext;

    public ShellAudioContext() { super(resolveSystemContext()); }

    private static synchronized Context resolveSystemContext() {
        if (systemContext != null) return systemContext;
        if (Process.myUid() != Process.SHELL_UID)
            throw new SecurityException("Recorder must be an authenticated shell-UID process");
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            if (thread == null) {
                Constructor<?> constructor = threadClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                thread = constructor.newInstance();
                Field currentField = threadClass.getDeclaredField("sCurrentActivityThread");
                currentField.setAccessible(true);
                currentField.set(null, thread);
                Field systemField = threadClass.getDeclaredField("mSystemThread");
                systemField.setAccessible(true);
                systemField.setBoolean(thread, true);
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
                    Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
                    Constructor<?> ctor = controllerClass.getDeclaredConstructor(internalClass);
                    ctor.setAccessible(true);
                    Object controller = ctor.newInstance(thread);
                    Field field = threadClass.getDeclaredField("mConfigurationController");
                    field.setAccessible(true);
                    field.set(thread, controller);
                } catch (ReflectiveOperationException ignored) {
                    // Optional OEM compatibility hook. The actual context must be nonnull.
                }
            }
            Method getSystemContext = threadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object value = getSystemContext.invoke(thread);
            if (!(value instanceof Context)) throw new IllegalStateException("No system Context");
            systemContext = (Context)value;
            return systemContext;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot bootstrap ADB shell Android context", e);
        }
    }
    @Override public String getPackageName() { return SHELL_PACKAGE; }
    @Override public String getOpPackageName() { return SHELL_PACKAGE; }
    @Override public Context getApplicationContext() { return this; }
    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID)
            .setPackageName(SHELL_PACKAGE).build();
    }
}
