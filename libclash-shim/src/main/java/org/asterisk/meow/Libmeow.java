package org.asterisk.meow;

/** JNI surface of libmeow (see libmeow/android/src/lib.rs). */
public final class Libmeow {
    static {
        System.loadLibrary("meow");
    }

    private Libmeow() {
    }

    public static native void nativeInit();

    public static native void nativeSetHomeDir(String dir);

    public static native String nativeVersion();

    public static native int nativeStart(String configPath, int fd, int mtu);

    public static native void nativeStop();

    public static native boolean nativeIsRunning();

    public static native long nativeUploadTotal();

    public static native long nativeDownloadTotal();

    public static native String nativeLastError();

    public static native void nativeSetProtectorCallback(Object tunInterface);

    public static native void nativeSetOwnerCallback(Object tunInterface);
}
