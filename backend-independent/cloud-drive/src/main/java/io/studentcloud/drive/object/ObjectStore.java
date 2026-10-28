package io.studentcloud.drive.object;

import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Provider-only capabilities. No account, HTTP session, SQL transaction, or public signed URL. */
public interface ObjectStore extends AutoCloseable {
    enum Provider { ALIYUN_OSS, S3 }
    record Connection(Provider provider,URI endpoint,String bucket,String region,boolean pathStyle,
                      String accessKey,String secretKey,String sessionToken){
        @Override public String toString(){return "ObjectStore.Connection[provider="+provider+", endpoint="+endpoint+", bucket="+bucket+", credentials=redacted]";}
    }
    record Item(String key,long size,Instant modifiedAt,String etag,String versionId,String operationId){
        public Item(String key,long size,Instant modifiedAt,String etag,String versionId){this(key,size,modifiedAt,etag,versionId,null);}
    }
    record Page(List<Item> objects,List<String> prefixes,String nextToken){
        public Page{objects=List.copyOf(objects);prefixes=List.copyOf(prefixes);}
    }
    record Receipt(String key,long size,String etag,String versionId){}
    /** Only provider-confirmed rejections may release a write reservation without reconciliation. */
    final class WriteFailure extends RuntimeException {
        private final String publicCode;private final int httpStatus;private final boolean mayHavePublished;
        public WriteFailure(String code,int status,String message,boolean mayHavePublished){
            super(message);this.publicCode=code;this.httpStatus=status;this.mayHavePublished=mayHavePublished;
        }
        public String publicCode(){return publicCode;}public int httpStatus(){return httpStatus;}
        public boolean mayHavePublished(){return mayHavePublished;}
    }
    record Read(Item item,InputStream input)implements AutoCloseable{
        @Override public void close()throws IOException{input.close();}
    }
    Page list(String prefix,String delimiter,String continuationToken,int limit);
    Optional<Item> head(String key);
    Read read(String key);
    /** Checks provider/versioning support. Never falls back to a racy HEAD-then-overwriting-PUT. */
    void requireCreateOnly();
    Receipt createOnly(String key,InputStream input,long size,String operationId);
    /** Only a random test-owned object receipt, never an arbitrary user object. */
    void cleanupProbe(Receipt receipt);
    @Override void close();
}
