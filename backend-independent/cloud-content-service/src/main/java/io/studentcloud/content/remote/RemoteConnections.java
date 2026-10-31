package io.studentcloud.content.remote;
import io.studentcloud.drive.object.ObjectStore;
/**
 * 向远程云盘服务提供对象存储连接、目录前缀与配置版本。
 * Internal content-owned configuration port; decrypted settings never pass through an HTTP response.
 */
public interface RemoteConnections {
 record Settings(long mountId,ObjectStore.Connection connection,String basePrefix,long version,boolean writeVerified){}
 Settings load(long mountId);
}
