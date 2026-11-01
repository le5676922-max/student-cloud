package io.studentcloud.drive.application;

/** Private namespaces belong to immutable account IDs, not to a temporary administrator role. */
public final class PersonalDriveRoot {
    private PersonalDriveRoot() {}
    public static boolean belongsTo(String path,long accountId) {
        return accountId>0 && (("/members/u"+accountId).equals(path) || ("/admins/u"+accountId).equals(path));
    }
}
