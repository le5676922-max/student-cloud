package im.tlgxy.cloud.module.learning.application.port;

/**
 * Access to a user's source document, never a server path or download URL.
 * Each operation must revalidate current authorization and the complete bounded file.
 */
public interface LearningFileAccess {
    int MAX_BYTES = 5 * 1024 * 1024;

    byte[] read(String storageKey, String requestedPath);
    String fingerprint(String storageKey, String requestedPath);
}
