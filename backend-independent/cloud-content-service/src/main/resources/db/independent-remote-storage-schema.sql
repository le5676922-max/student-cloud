CREATE TABLE sc_remote_storage (
 mount_id BIGINT NOT NULL PRIMARY KEY,
 storage_type VARCHAR(20) NOT NULL, provider VARCHAR(20) NOT NULL,
 endpoint VARCHAR(512) NOT NULL, bucket_name VARCHAR(63) NOT NULL, region VARCHAR(64) NOT NULL,
 base_prefix VARCHAR(500) NOT NULL,path_style BOOLEAN NOT NULL,
 credentials_ciphertext TEXT NOT NULL,config_version BIGINT NOT NULL,
 write_verified BOOLEAN NOT NULL,validated_at BIGINT,
 CONSTRAINT sc_remote_mount_fk FOREIGN KEY(mount_id) REFERENCES sc_drive_mount(id)
);
