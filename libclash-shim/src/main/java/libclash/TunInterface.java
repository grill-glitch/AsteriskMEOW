package libclash;

/** Mirrors the AAR's TunInterface callbacks. */
public interface TunInterface {
    void markSocket(int fd);

    int querySocketUid(int protocol, String source, String target);
}
