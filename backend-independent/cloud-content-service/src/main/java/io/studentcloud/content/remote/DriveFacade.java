package io.studentcloud.content.remote;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import io.studentcloud.drive.application.*;
import java.io.InputStream;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
/** Keeps HTTP contracts stable while local and object-store use cases remain independently testable. */
public final class DriveFacade implements DriveOperations {
 private final JdbcTemplate jdbc;private final MountService local;private final RemoteDriveService remote;
 public DriveFacade(JdbcTemplate jdbc,MountService local,RemoteDriveService remote){this.jdbc=jdbc;this.local=local;this.remote=remote;}
 private DriveOperations route(String key){var engines=jdbc.queryForList("SELECT engine FROM sc_drive_mount WHERE mount_key=?",String.class,key);if(engines.size()!=1)throw new ApplicationFault("DRIVE_FORBIDDEN",403,"存储源不可访问");return switch(engines.getFirst()){case "LOCAL"->local;case "S3","ALIYUN_OSS"->remote;default->throw new ApplicationFault("DRIVE_ENGINE_UNAVAILABLE",501,"此存储协议尚未开放");};}
 public List<MountView> listMounts(Actor a){var result=new ArrayList<>(local.listLocalMounts(a));result.addAll(remote.listMounts(a));return List.copyOf(result);}
 public List<FileRecord> list(Actor a,String k,DrivePath p,boolean r){return route(k).list(a,k,p,r);}public Optional<FileRecord> stat(Actor a,String k,DrivePath p){return route(k).stat(a,k,p);}
 public Set<String> directoryCapabilities(Actor a,String k,DrivePath p){return route(k).directoryCapabilities(a,k,p);}public void prepareUpload(Actor a,String k,DrivePath p,long s){route(k).prepareUpload(a,k,p,s);}public String uploadAuthorization(Actor a,String k,DrivePath p,long s){return route(k).uploadAuthorization(a,k,p,s);}
 public FileRecord mkdir(Actor a,String k,DrivePath p){return route(k).mkdir(a,k,p);}public FileRecord upload(Actor a,String k,DrivePath p,InputStream in,long s){return route(k).upload(a,k,p,in,s);}public ReadHandle readWithMetadata(Actor a,String k,DrivePath p){return route(k).readWithMetadata(a,k,p);}
 public FileRecord copy(Actor a,String k,DrivePath s,DrivePath t){return route(k).copy(a,k,s,t);}public FileRecord move(Actor a,String k,DrivePath s,DrivePath t){return route(k).move(a,k,s,t);}public FileRecord rename(Actor a,String k,DrivePath p,String n){return route(k).rename(a,k,p,n);}public void delete(Actor a,String k,DrivePath p){route(k).delete(a,k,p);}
 public FileCatalog.Page catalogPage(Actor a,String k,DrivePath p,int page,int size,String q,boolean r){return route(k).catalogPage(a,k,p,page,size,q,r);}public List<MountService.TrashView> recycleBin(Actor a,String k){return route(k).recycleBin(a,k);}public void restoreRecycled(Actor a,String k,String id){route(k).restoreRecycled(a,k,id);}public void purgeRecycled(Actor a,String k,String id){route(k).purgeRecycled(a,k,id);}
}
