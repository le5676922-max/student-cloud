package io.studentcloud.content.remote;
import io.studentcloud.drive.object.ObjectStore;
/** Internal content-owned configuration port; decrypted settings never pass through an HTTP response. */
public interface RemoteConnections {
 record Settings(long mountId,ObjectStore.Connection connection,String basePrefix,long version,boolean writeVerified){}
 Settings load(long mountId);
}
