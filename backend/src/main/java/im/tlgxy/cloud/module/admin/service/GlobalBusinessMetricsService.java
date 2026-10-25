package im.tlgxy.cloud.module.admin.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.service.TenantSecurityService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregate-only read model. It never lists files, user profiles, source credentials or conversation text. */
@Service
public class GlobalBusinessMetricsService {
    private final JdbcTemplate jdbc;
    private final TenantSecurityService security;
    private final LinkedHashMap<Integer, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private static final long CACHE_MILLIS = 30000;
    private record Cached(long until, Metrics value) { }
    public record Bucket(String key, long count) { }
    public record Courses(long total, long active, long members, long materials, long usedBytes, long quotaBytes) { }
    public record WorkspaceUsage(int tenantId, String name, long courses, long usedBytes, long quotaBytes) { }
    public record Metrics(long generatedAt, int cacheSeconds, Integer tenantId,
                          List<Bucket> storageTypes, List<Bucket> pendingApprovals,
                          List<Bucket> feedbackStatuses, List<Bucket> knowledgeScopes,
                          Courses courses, List<WorkspaceUsage> courseUsageByWorkspace,
                          Long personalStorageUsedBytes) { }

    public GlobalBusinessMetricsService(JdbcTemplate jdbc, TenantSecurityService security) { this.jdbc = jdbc; this.security = security; }

    public Metrics get(Integer tenantId) {
        // A cache hit must never bypass a changed administrator role or disabled identity.
        security.requireSystemAdmin();
        if (tenantId != null && tenantId < 1) throw new BizException("工作空间标识不正确");
        int key = tenantId == null ? 0 : tenantId;
        synchronized (cache) {
            long now = System.currentTimeMillis();
            Cached cached = cache.get(key);
            if (cached != null && now < cached.until()) return cached.value();
            if (tenantId != null && scalar("SELECT COUNT(*) FROM tenant WHERE id=?", tenantId) != 1)
                throw new BizException("工作空间不存在");
            Metrics value = load(tenantId, now);
            cache.put(key, new Cached(System.currentTimeMillis() + CACHE_MILLIS, value));
            while (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
            return value;
        }
    }

    private Metrics load(Integer tenant, long now) {
        String where = tenant == null ? "" : " WHERE tenant_id=?";
        Object[] args = tenant == null ? new Object[0] : new Object[]{tenant};
        List<Bucket> types = buckets("SELECT `type` bucket,COUNT(*) n FROM storage_source" + where + " GROUP BY `type` ORDER BY n DESC,bucket LIMIT 40", args);
        var pending = new ArrayList<Bucket>();
        pending.add(new Bucket("LOCAL_STORAGE", scalar("SELECT COUNT(*) FROM storage_local_request WHERE status='PENDING'" + andTenant(tenant, "tenant_id"), args)));
        pending.add(new Bucket("MEMBER_CLOUD", scalar("SELECT COUNT(*) FROM workspace_cloud_request WHERE status='PENDING'" + andTenant(tenant, "tenant_id"), args)));
        pending.add(new Bucket("COURSE_UPLOAD", scalar("SELECT COUNT(*) FROM shared_course_member m JOIN shared_course c ON c.id=m.course_id WHERE m.status='JOINED' AND m.upload_status='PENDING'" + andTenant(tenant, "c.tenant_id"), args)));
        List<Bucket> feedback = buckets("SELECT status bucket,COUNT(*) n FROM ai_answer_feedback" + where + " GROUP BY status ORDER BY status", args);
        String knowledgeFilter = tenant == null ? "" : " AND (r.tenant_id=? OR c.tenant_id=?)";
        Object[] knowledgeArgs = tenant == null ? new Object[0] : new Object[]{tenant, tenant};
        List<Bucket> knowledge = buckets("SELECT r.scope bucket,COUNT(*) n FROM ai_curated_knowledge k JOIN ai_curated_knowledge_revision r ON r.feedback_id=k.feedback_id AND r.version=k.version LEFT JOIN shared_course c ON c.id=r.course_id WHERE k.state='PUBLISHED'" + knowledgeFilter + " GROUP BY r.scope ORDER BY r.scope", knowledgeArgs);
        List<Courses> courseRows = jdbc.query("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN enabled=1 THEN 1 ELSE 0 END),0) active,COALESCE(SUM(used_bytes),0) used_bytes,COALESCE(SUM(quota_bytes),0) quota_bytes FROM shared_course" + where,
                (row, i) -> new Courses(row.getLong("total"), row.getLong("active"), 0, 0, row.getLong("used_bytes"), row.getLong("quota_bytes")), args);
        Courses summary = courseRows.getFirst();
        long members = scalar("SELECT COUNT(*) FROM shared_course_member m JOIN shared_course c ON c.id=m.course_id WHERE m.status='JOINED'" + andTenant(tenant, "c.tenant_id"), args);
        long materials = scalar("SELECT COUNT(*) FROM shared_course_material m JOIN shared_course c ON c.id=m.course_id WHERE m.state='READY'" + andTenant(tenant, "c.tenant_id"), args);
        Courses courses = new Courses(summary.total(), summary.active(), members, materials, summary.usedBytes(), summary.quotaBytes());
        List<WorkspaceUsage> usage = jdbc.query("SELECT c.tenant_id,COALESCE(t.name,'已删除空间') name,COUNT(*) courses,SUM(c.used_bytes) used_bytes,SUM(c.quota_bytes) quota_bytes FROM shared_course c LEFT JOIN tenant t ON t.id=c.tenant_id" + (tenant == null ? "" : " WHERE c.tenant_id=?")
                        + " GROUP BY c.tenant_id,t.name ORDER BY used_bytes DESC,c.tenant_id LIMIT 10",
                (row, i) -> new WorkspaceUsage(row.getInt("tenant_id"), row.getString("name"), row.getLong("courses"), row.getLong("used_bytes"), row.getLong("quota_bytes")), args);
        return new Metrics(now, 30, tenant, types, List.copyOf(pending), feedback, knowledge, courses, usage, null);
    }

    private static String andTenant(Integer tenant, String column) { return tenant == null ? "" : " AND " + column + "=?"; }
    private long scalar(String sql, Object... args) { Long value = jdbc.queryForObject(sql, Long.class, args); return value == null ? 0 : value; }
    private List<Bucket> buckets(String sql, Object... args) {
        return jdbc.query(sql, (row, i) -> new Bucket(row.getString("bucket"), row.getLong("n")), args);
    }
}
