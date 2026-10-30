package org.asterisk.meow;

/**
 * JNI surface of libmeow. The native side lives in
 * libmeow/android/src/lib.rs; names here must stay in sync with the
 * Java_org_asterisk_meow_Libmeow_* exports.
 */
public final class Libmeow {
    static {
        System.loadLibrary("meow");
    }

    private Libmeow() {}

    public static native void nativeInit();

    public static native String nativeVersion();

    /**
     * Starts the meow engine and its TUN listener on an fd from
     * VpnService.Builder.establish(). Ownership of the fd transfers to
     * the listener.
     *
     * @return 0 on success, -1 on failure (see nativeLastError()).
     */
    public static native int nativeStart(String configPath, int fd, int mtu);

    public static native void nativeStop();

    public static native boolean nativeIsRunning();

    public static native long nativeUploadTotal();

    public static native long nativeDownloadTotal();

    public static native String nativeLastError();

    /** Every outbound socket calls service.protect(fd) before connect/bind. */
    public static native void nativeSetProtector(Object vpnService);

    public static native void nativeClearProtector();

    /** Per-flow ConnectivityManager.getConnectionOwnerUid lookup (API 29+). */
    public static native void nativeSetOwnerResolver(Object vpnService);
}
