package libclash;

public interface FetchCallback extends Completion {
    void complete(String error);

    void report(String statusJson);
}
