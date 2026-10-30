package libclash;

/** Traffic counters returned by the bridge (matches the AAR property names). */
public final class Traffic {
    private long upload;
    private long download;

    public Traffic() {
    }

    public Traffic(long upload, long download) {
        this.upload = upload;
        this.download = download;
    }

    public long getUpload() {
        return upload;
    }

    public void setUpload(long upload) {
        this.upload = upload;
    }

    public long getDownload() {
        return download;
    }

    public void setDownload(long download) {
        this.download = download;
    }
}
