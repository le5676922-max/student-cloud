package io.studentcloud.drive.object;
@FunctionalInterface
public interface ObjectStoreFactory {ObjectStore open(ObjectStore.Connection connection);}
