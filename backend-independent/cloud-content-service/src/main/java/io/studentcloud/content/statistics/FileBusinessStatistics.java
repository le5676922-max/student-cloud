package io.studentcloud.content.statistics;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Content-owned aggregates only. Identity is resolved once through its owning port, never SQL joins. */
@Service
public final class FileBusinessStatistics {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final int MAX_TREND_ROWS=10_000;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccountDirectory accounts;
    private final Semaphore slots=new Semaphore(2);
    public FileBusinessStatistics(JdbcTemplate jdbc,Clock clock,AccountDirectory accounts){this.jdbc=jdbc;this.clock=clock;this.accounts=accounts;}
    public record Metric(boolean available,Long value,String note){}
    public record Day(String date,long count){}
    public record Trend(boolean available,List<Day> days,String note){}
    public record Overview(long generatedAt,String timeZone,String scope,Long workspaceId,String courseScope,
                           boolean dataComplete,String usageBasis,Map<String,Boolean> resourceAvailability,
                           Map<String,Metric> metrics,Map<String,Trend> trends){}

    public Overview overview(Actor supplied,int days){
        Actor actor=accounts.current(supplied);
        if(actor.role()!=Role.ADMIN&&(actor.role()!=Role.TENANT_ADMIN||actor.workspaceId()==null||!accounts.workspaceEnabled(actor.workspaceId())))throw denied();
        if(days<1||days>32)throw new ApplicationFault("41000",400,"趋势范围须为1至32天");
        if(!slots.tryAcquire())throw new ApplicationFault("STATISTICS_BUSY",429,"统计读取繁忙，请稍后重试");
        try{
            boolean global=actor.role()==Role.ADMIN;long now=clock.millis();
            var metrics=new LinkedHashMap<String,Metric>();var trends=new LinkedHashMap<String,Trend>();
            String mountScope=global?"":" AND workspace_id=?";
            Object[] workspace=global?new Object[0]:new Object[]{actor.workspaceId()};
            metrics.put("mounts",metric("SELECT COUNT(*) FROM sc_drive_mount WHERE 1=1"+mountScope,workspace));
            metrics.put("enabledMounts",metric("SELECT COUNT(*) FROM sc_drive_mount WHERE enabled=TRUE"+mountScope,workspace));
            // Course schemas own an owner ID, not a workspace snapshot. Workspace managers therefore see
            // precisely their own created courses; another manager's or a joined course is never included.
            String courseScope=global?"":" AND c.owner_id=?";
            Object[] courseOwner=global?new Object[0]:new Object[]{actor.id()};
            metrics.put("courses",metric("SELECT COUNT(*) FROM sc_shared_course c WHERE c.deleted_at IS NULL"+courseScope,courseOwner));
            metrics.put("courseUsedBytes",metric("SELECT COALESCE(SUM(c.used_bytes),0) FROM sc_shared_course c WHERE c.deleted_at IS NULL"+courseScope,courseOwner));
            metrics.put("readyMaterials",metric("SELECT COUNT(*) FROM sc_course_material m JOIN sc_shared_course c ON c.id=m.course_id WHERE c.deleted_at IS NULL AND m.state='READY'"+courseScope,courseOwner));
            metrics.put("materialBytes",metric("SELECT COALESCE(SUM(m.size_bytes),0) FROM sc_course_material m JOIN sc_shared_course c ON c.id=m.course_id WHERE c.deleted_at IS NULL AND m.state='READY'"+courseScope,courseOwner));
            metrics.put("activeUploads",metric("SELECT COUNT(*) FROM sc_upload_session WHERE state IN('OPEN','COMPLETING') AND expires_at>?"+mountScope,append(new Object[]{now},workspace)));
            // Reservations in completed/expired sessions can still occupy staging until cleanup confirms it.
            metrics.put("stagingBytes",metric("SELECT COALESCE(SUM(staging_reserved),0) FROM sc_upload_session WHERE 1=1"+mountScope,workspace));
            metrics.put("activeShares",metric("SELECT COUNT(*) FROM sc_file_share WHERE revoked_at IS NULL AND expires_at>?"+mountScope,append(new Object[]{now},workspace)));
            metrics.put("shares",metric("SELECT COUNT(*) FROM sc_file_share WHERE 1=1"+mountScope,workspace));
            String occupiedTrash="status IN('TRASHED','PREPARING','RESTORING','UNCERTAIN','PURGING')";
            metrics.put("trashItems",metric("SELECT COUNT(*) FROM sc_drive_trash WHERE "+occupiedTrash+mountScope,workspace));
            metrics.put("trashBytes",metric("SELECT COALESCE(SUM(byte_size),0) FROM sc_drive_trash WHERE "+occupiedTrash+mountScope,workspace));
            trends.put("uploads",trend("SELECT created_at FROM sc_upload_session WHERE created_at>=? AND created_at<=?"+mountScope,workspace,now,days));
            trends.put("shares",trend("SELECT created_at FROM sc_file_share WHERE created_at>=? AND created_at<=?"+mountScope,workspace,now,days));
            trends.put("materials",trend("SELECT m.created_at FROM sc_course_material m JOIN sc_shared_course c ON c.id=m.course_id WHERE m.created_at>=? AND m.created_at<=? AND m.state='READY' AND c.deleted_at IS NULL"+courseScope,courseOwner,now,days));
            var available=new LinkedHashMap<String,Boolean>();
            available.put("mounts",metrics.get("mounts").available()&&metrics.get("enabledMounts").available());
            available.put("courses",metrics.get("courses").available()&&metrics.get("courseUsedBytes").available());
            available.put("materials",metrics.get("readyMaterials").available()&&metrics.get("materialBytes").available());
            available.put("uploads",metrics.get("activeUploads").available()&&metrics.get("stagingBytes").available());
            available.put("shares",metrics.get("shares").available()&&metrics.get("activeShares").available());
            available.put("trash",metrics.get("trashItems").available()&&metrics.get("trashBytes").available());
            boolean complete=available.values().stream().allMatch(Boolean::booleanValue)&&trends.values().stream().allMatch(Trend::available);
            return new Overview(now,ZONE.getId(),global?"GLOBAL":"WORKSPACE",global?null:actor.workspaceId(),global?"ALL_CREATED":"OWN_CREATED",
                    complete,"OWNED_RECORDS_NOT_PHYSICAL_USAGE",Collections.unmodifiableMap(available),Collections.unmodifiableMap(metrics),Collections.unmodifiableMap(trends));
        }finally{slots.release();}
    }
    private Metric metric(String sql,Object...args){
        try{Long count=jdbc.queryForObject(sql,Long.class,args);return count==null?unavailable():new Metric(true,count,"");}
        catch(DataAccessException unavailable){return unavailable();}
    }
    private static Metric unavailable(){return new Metric(false,null,"数据源暂不可用，请刷新重试");}
    private Trend trend(String sql,Object[] scope,long now,int count){
        LocalDate today=Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate();LocalDate first=today.minusDays(count-1);
        long from=first.atStartOfDay(ZONE).toInstant().toEpochMilli();
        try{
            List<Long> rows=jdbc.queryForList(sql+" ORDER BY 1 LIMIT "+(MAX_TREND_ROWS+1),Long.class,append(new Object[]{from,now},scope));
            if(rows.size()>MAX_TREND_ROWS)return new Trend(false,List.of(),"保留记录超出单次趋势预算，请缩小日期范围");
            Map<LocalDate,Long> totals=new HashMap<>();
            for(long created:rows)totals.merge(Instant.ofEpochMilli(created).atZone(ZONE).toLocalDate(),1L,Long::sum);
            List<Day> days=new ArrayList<>();for(int day=0;day<count;day++){LocalDate date=first.plusDays(day);days.add(new Day(date.toString(),totals.getOrDefault(date,0L)));}
            return new Trend(true,List.copyOf(days),"按当前保留记录的创建日期统计；课程资料仅计当前可用记录");
        }catch(DataAccessException unavailable){return new Trend(false,List.of(),"趋势数据源暂不可用，请刷新重试");}
    }
    private static Object[] append(Object[] before,Object[] after){Object[] result=Arrays.copyOf(before,before.length+after.length);System.arraycopy(after,0,result,before.length,after.length);return result;}
    private static ApplicationFault denied(){return new ApplicationFault("40300",403,"仅管理员可查看相应范围的文件业务统计");}
}
