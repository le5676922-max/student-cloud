package io.studentcloud.drive.application;
import io.studentcloud.account.Actor;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.*;
import java.io.InputStream;
import java.util.*;

/** Authorization-aware application port shared by local and remote adapters. */
public interface DriveOperations {
 List<MountView> listMounts(Actor actor);
 List<FileRecord> list(Actor actor,String key,DrivePath path,boolean refresh);
 default List<FileRecord> list(Actor actor,String key,DrivePath path){return list(actor,key,path,false);}
 Optional<FileRecord> stat(Actor actor,String key,DrivePath path);
 Set<String> directoryCapabilities(Actor actor,String key,DrivePath path);
 void prepareUpload(Actor actor,String key,DrivePath path,long size);
 String uploadAuthorization(Actor actor,String key,DrivePath path,long size);
 FileRecord mkdir(Actor actor,String key,DrivePath path);
 FileRecord upload(Actor actor,String key,DrivePath path,InputStream input,long size);
 ReadHandle readWithMetadata(Actor actor,String key,DrivePath path);
 default FileRecord copy(Actor actor,String key,DrivePath source,DrivePath target){throw unsupported();}
 default FileRecord move(Actor actor,String key,DrivePath source,DrivePath target){throw unsupported();}
 default FileRecord rename(Actor actor,String key,DrivePath source,String name){throw unsupported();}
 default void delete(Actor actor,String key,DrivePath path){throw unsupported();}
 default FileCatalog.Page catalogPage(Actor actor,String key,DrivePath path,int page,int size,String query,boolean refresh){throw unsupported();}
 default List<MountService.TrashView> recycleBin(Actor actor,String key){throw unsupported();}
 default void restoreRecycled(Actor actor,String key,String id){throw unsupported();}
 default void purgeRecycled(Actor actor,String key,String id){throw unsupported();}
 private static ApplicationFault unsupported(){return new ApplicationFault("STORAGE_CAPABILITY_UNAVAILABLE",501,"此存储源尚未开放这项能力，请使用当前可用的目录和读写功能");}
}
