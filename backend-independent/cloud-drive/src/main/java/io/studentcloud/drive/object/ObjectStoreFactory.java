package io.studentcloud.drive.object;
/** 根据连接配置创建对象存储访问实例。 */
@FunctionalInterface
public interface ObjectStoreFactory {ObjectStore open(ObjectStore.Connection connection);}
