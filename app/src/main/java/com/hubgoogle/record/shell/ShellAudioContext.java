package com.hubgoogle.record.shell;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;

/**
 * Supplies an AudioRecord attribution matching the *actual* shell-UID process.
 * This is not privilege escalation: the daemon can only run if the user has
 * paired their own ADB and the OEM actually grants audio capture to shell.
 *
 * Design independently adapted from the idea in scrcpy FakeContext (Apache-2.0).
 */
public final class ShellAudioContext extends ContextWrapper {
    private static final String SHELL_PACKAGE = "com.android.shell";
    public ShellAudioContext() { super(null); }
    @Override public String getPackageName() { return SHELL_PACKAGE; }
    @Override public String getOpPackageName() { return SHELL_PACKAGE; }
    @Override public Context getApplicationContext() { return this; }
    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID)
            .setPackageName(SHELL_PACKAGE).build();
    }
}
