package libclash;

/** Mirrors the gomobile ContentResolver interface (content:// fd open). */
public interface ContentResolver {
    int openContent(String uri) throws Exception;
}
