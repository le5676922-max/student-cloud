package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.net.IDN;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Freshly authorized global administration read/write adapter for the platform-owned identity database. */
public final class PlatformAdministrationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final PasswordHasher passwords = new PasswordHasher();

    public PlatformAdministrationService(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(15);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Page<T>(List<T> records, long total, int page, int pageSize, String accountModel) {
        public Page { records = List.copyOf(records); }
    }
    public record Workspace(long id, String name, String ownerAccountId, long members, long workspaceAdmins,
                            long pendingMembers, Long storageSources, boolean enabled, long createTime) { }
    public record Member(long id, String accountId, String username, String nickname, String email, String role,
                         Long tenantId, boolean enable, String membershipStatus, long createTime) { }
    public record TenantInput(Long id, String name, boolean enable) { }
    public record AdministratorInput(Long id, String username, String email, String password, String oldPassword,
                                     String nickname, Long tenantId, String role, Boolean enable) {
        public AdministratorInput(String username,String email,String password,String nickname,Long tenantId,String role){this(null,username,email,password,null,nickname,tenantId,role,null);}
    }

    public Page<Workspace> workspaces(Actor supplied, String keyword, Boolean enabled, Long workspaceId, int page, int pageSize) {
        requireAdmin(supplied);
        page(page, pageSize);
        String filter = safeSearch(keyword);
        String like = "%" + escapeLike(filter.toLowerCase(Locale.ROOT)) + "%";
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (!filter.isEmpty()) { where.append(" AND (LOWER(w.name) LIKE ? ESCAPE '!' OR CAST(w.id AS CHAR) LIKE ? ESCAPE '!')"); args.add(like); args.add(like); }
        if (enabled != null) { where.append(" AND w.enabled=?"); args.add(enabled); }
        if (workspaceId != null) { if (workspaceId < 1) throw invalid("工作空间 ID 无效"); where.append(" AND w.id=?"); args.add(workspaceId); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sc_workspace w" + where, Long.class, args.toArray());
        args.add(pageSize); args.add((page - 1) * pageSize);
        List<Workspace> records = jdbc.query("SELECT w.id,w.name,w.enabled,w.created_at,"
                + "(SELECT a.account_id FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN' ORDER BY a.id LIMIT 1) owner_id,"
                + "(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='USER') members,"
                + "(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN') admins,"
                + "(SELECT COUNT(*) FROM sc_workspace_join_request r WHERE r.workspace_id=w.id AND r.status='PENDING') pending"
                + " FROM sc_workspace w" + where + " ORDER BY w.id LIMIT ? OFFSET ?", (r,n)->new Workspace(
                r.getLong("id"),r.getString("name"),r.getString("owner_id"),r.getLong("members"),r.getLong("admins"),
                r.getLong("pending"),null,r.getBoolean("enabled"),r.getLong("created_at")),args.toArray());
        return new Page<>(records, Objects.requireNonNullElse(total,0L), page, pageSize,"independent");
    }

    public List<Workspace> tenantList(Actor supplied) {
        requireAdmin(supplied);
        return jdbc.query("SELECT w.id,w.name,w.enabled,w.created_at,(SELECT a.account_id FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN' ORDER BY a.id LIMIT 1) owner_id,"
                + "(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='USER') members,(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN') admins,"
                + "(SELECT COUNT(*) FROM sc_workspace_join_request r WHERE r.workspace_id=w.id AND r.status='PENDING') pending FROM sc_workspace w ORDER BY w.id LIMIT 10000",
                (r,n)->new Workspace(r.getLong("id"),r.getString("name"),r.getString("owner_id"),r.getLong("members"),r.getLong("admins"),r.getLong("pending"),null,r.getBoolean("enabled"),r.getLong("created_at")));
    }

    public Page<Member> workspaceUsers(Actor supplied, long tenantId, String role, String keyword, int page, int pageSize) {
        requireAdmin(supplied);
        page(page,pageSize);
        if (tenantId < 1 || jdbc.queryForList("SELECT id FROM sc_workspace WHERE id=?",Long.class,tenantId).isEmpty()) throw missingWorkspace();
        String normalizedRole = switch (role == null ? "TENANT_ADMIN" : role.toUpperCase(Locale.ROOT)) {
            case "TA", "TENANT_ADMIN" -> "TENANT_ADMIN";
            case "USER" -> "USER";
            default -> throw invalid("角色参数无效");
        };
        String filter = safeSearch(keyword), like = "%" + escapeLike(filter.toLowerCase(Locale.ROOT)) + "%";
        String condition = "a.workspace_id=? AND a.role=?";
        List<Object> parameters = new ArrayList<>(List.of(tenantId,normalizedRole));
        if (!filter.isEmpty()) { condition += " AND (LOWER(a.username) LIKE ? ESCAPE '!' OR LOWER(COALESCE(a.email,'')) LIKE ? ESCAPE '!'"
                + " OR LOWER(a.account_id) LIKE ? ESCAPE '!')"; parameters.add(like); parameters.add(like); parameters.add(like); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sc_account a WHERE " + condition,Long.class,parameters.toArray());
        parameters.add(pageSize); parameters.add((page-1)*pageSize);
        List<Member> rows = jdbc.query("SELECT a.id,a.account_id,a.username,a.email,a.role,a.workspace_id,a.enabled,a.created_at,p.nickname,"
                + "r.status FROM sc_account a LEFT JOIN sc_public_profile p ON p.account_id=a.id LEFT JOIN sc_workspace_join_request r "
                + "ON r.account_id=a.id AND r.workspace_id=a.workspace_id WHERE " + condition + " ORDER BY a.id LIMIT ? OFFSET ?",
                (r,n)->new Member(r.getLong("id"),r.getString("account_id"),r.getString("username"),
                        Objects.requireNonNullElse(r.getString("nickname"),r.getString("username")),r.getString("email"),r.getString("role"),
                        r.getObject("workspace_id",Long.class),r.getBoolean("enabled"),membership(r.getString("status")),r.getLong("created_at")),parameters.toArray());
        return new Page<>(rows,Objects.requireNonNullElse(total,0L),page,pageSize,"independent");
    }

    public Workspace saveWorkspace(Actor supplied, TenantInput input) {
        requireAdmin(supplied);
        if (input == null) throw invalid("工作空间数据无效");
        String name = text(input.name(),80,"工作空间名称须为 1 至 80 字符");
        return Objects.requireNonNull(transactions.execute(status -> {
            requireAdmin(supplied);
            long id;
            if (input.id() == null || input.id() == 0) {
                GeneratedKeyHolder key = new GeneratedKeyHolder();
                jdbc.update(connection->{PreparedStatement st=connection.prepareStatement("INSERT INTO sc_workspace(name,enabled,created_at) VALUES(?,?,?)",Statement.RETURN_GENERATED_KEYS);
                    st.setString(1,name);st.setBoolean(2,input.enable());st.setLong(3,clock.millis());return st;},key);
                id=Objects.requireNonNull(key.getKey()).longValue();
            } else {
                if(input.id()<1) throw invalid("工作空间 ID 无效");
                int changed=jdbc.update("UPDATE sc_workspace SET name=?,enabled=? WHERE id=?",name,input.enable(),input.id());
                if(changed!=1) throw missingWorkspace();
                id=input.id();
                if(!input.enable()) jdbc.update("DELETE FROM sc_session WHERE account_id IN (SELECT id FROM sc_account WHERE workspace_id=?)",id);
            }
            return workspaceById(id);
        }));
    }

    public Member createWorkspaceAdministrator(Actor supplied, AdministratorInput input) {
        requireAdmin(supplied);
        if(input==null || input.tenantId()==null || input.tenantId()<1 || !"TENANT_ADMIN".equals(input.role())) throw invalid("只能创建指定工作空间的管理员");
        String username=normalizeUsername(input.username()), email=input.email()==null||input.email().isBlank()?null:normalizeEmail(input.email()), nickname=input.nickname()==null?null:text(input.nickname(),100,"昵称不能超过 100 字符");
        if(!PasswordHasher.validNewPassword(input.password())) throw invalid("密码须为 8 至 128 字符");
        try {
            return Objects.requireNonNull(transactions.execute(status->{
                requireAdmin(supplied);
                if(jdbc.queryForList("SELECT id FROM sc_workspace WHERE id=? AND enabled=TRUE FOR UPDATE",Long.class,input.tenantId()).isEmpty()) throw missingWorkspace();
                Integer duplicates=email==null?jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE LOWER(username)=?",Integer.class,username)
                        :jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE LOWER(username)=? OR LOWER(email)=?",Integer.class,username,email);
                if(duplicates!=null && duplicates>0) throw duplicateAccount();
                long now=clock.millis();
                GeneratedKeyHolder key=new GeneratedKeyHolder();
                String accountId="SC"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
                jdbc.update(connection->{PreparedStatement st=connection.prepareStatement("INSERT INTO sc_account(account_id,username,email,password_hash,role,workspace_id,enabled,session_version,created_at) VALUES(?,?,?,?,'TENANT_ADMIN',?,TRUE,1,?)",Statement.RETURN_GENERATED_KEYS);
                    st.setString(1,accountId);st.setString(2,username);st.setString(3,email);st.setString(4,passwords.hash(input.password()));st.setLong(5,input.tenantId());st.setLong(6,now);return st;},key);
                long id=Objects.requireNonNull(key.getKey()).longValue();
                if(nickname!=null) jdbc.update("INSERT INTO sc_public_profile(account_id,nickname,email_verified_at) VALUES(?,?,0)",id,nickname);
                return new Member(id,accountId,username,Objects.requireNonNullElse(nickname,username),email,"TENANT_ADMIN",input.tenantId(),true,"ACTIVE",now);
            }));
        } catch(DuplicateKeyException duplicate){throw duplicateAccount();}
    }

    /** Existing-account saves can edit only the caller's own profile or a workspace administrator's profile/status. */
    public Member saveAdministrator(Actor supplied, AdministratorInput input) {
        Actor current=requireAdmin(supplied);
        if(input==null || input.id()==null) return createWorkspaceAdministrator(supplied,input);
        String username=normalizeUsername(input.username());
        String nickname=input.nickname()==null?null:text(input.nickname(),100,"昵称不能超过 100 字符");
        return Objects.requireNonNull(transactions.execute(status->{
            requireAdmin(supplied);
            List<Member> targets=jdbc.query("SELECT a.id,a.account_id,a.username,a.email,a.role,a.workspace_id,a.enabled,a.created_at,p.nickname,r.status "
                    + "FROM sc_account a LEFT JOIN sc_public_profile p ON p.account_id=a.id LEFT JOIN sc_workspace_join_request r ON r.account_id=a.id AND r.workspace_id=a.workspace_id "
                    + "WHERE a.id=? FOR UPDATE",(r,n)->new Member(r.getLong("id"),r.getString("account_id"),r.getString("username"),r.getString("nickname"),r.getString("email"),r.getString("role"),r.getObject("workspace_id",Long.class),r.getBoolean("enabled"),membership(r.getString("status")),r.getLong("created_at")),input.id());
            if(targets.size()!=1) throw missingAccount();
            Member target=targets.getFirst();boolean self=target.id()==current.id();
            if(!self && !"TENANT_ADMIN".equals(target.role())) throw forbidden();
            if(!self && (input.password()!=null&&!input.password().isBlank()||input.oldPassword()!=null&&!input.oldPassword().isBlank())) throw forbidden();
            Integer duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE LOWER(username)=? AND id<>?",Integer.class,username,target.id());
            if(duplicate!=null&&duplicate>0) throw duplicateAccount();
            jdbc.update("UPDATE sc_account SET username=? WHERE id=?",username,target.id());
            if(nickname!=null){int found=jdbc.update("UPDATE sc_public_profile SET nickname=? WHERE account_id=?",nickname,target.id());if(found==0)jdbc.update("INSERT INTO sc_public_profile(account_id,nickname,email_verified_at) VALUES(?,?,0)",target.id(),nickname);}
            boolean enable=input.enable()==null?target.enable():input.enable();
            if("ADMIN".equals(target.role())) enable=true;
            if(enable!=target.enable()){
                if(enable && target.tenantId()!=null && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM sc_workspace WHERE id=?",Boolean.class,target.tenantId()))) throw missingWorkspace();
                jdbc.update("UPDATE sc_account SET enabled=?,session_version=session_version+1 WHERE id=?",enable,target.id());
                if(!enable)jdbc.update("DELETE FROM sc_session WHERE account_id=?",target.id());
            }
            if(input.password()!=null&&!input.password().isBlank()){
                if(!self || input.oldPassword()==null || input.oldPassword().isBlank()) throw invalid("修改本人密码需要提供当前密码");
                List<String> hashes=jdbc.queryForList("SELECT password_hash FROM sc_account WHERE id=?",String.class,target.id());
                if(hashes.size()!=1 || !passwords.verify(input.oldPassword(),hashes.getFirst())) throw new ApplicationFault("42000",401,"当前密码不正确");
                if(!PasswordHasher.validNewPassword(input.password())) throw invalid("密码须为 8 至 128 字符");
                jdbc.update("UPDATE sc_account SET password_hash=?,session_version=session_version+1 WHERE id=?",passwords.hash(input.password()),target.id());
                jdbc.update("DELETE FROM sc_session WHERE account_id=?",target.id());
            }
            return targetById(target.id());
        }));
    }

    public Member user(Actor supplied, long targetId) {
        Actor current=fresh(supplied);
        if(targetId<1) throw missingAccount();
        Member member=targetById(targetId);
        if(current.role()==Role.ADMIN && ("TENANT_ADMIN".equals(member.role()) || current.id()==targetId)) return member;
        if(current.id()==targetId) return member;
        throw forbidden();
    }

    private Member targetById(long targetId) {
        List<Member> rows=jdbc.query("SELECT a.id,a.account_id,a.username,a.email,a.role,a.workspace_id,a.enabled,a.created_at,p.nickname,r.status "
                + "FROM sc_account a LEFT JOIN sc_public_profile p ON p.account_id=a.id LEFT JOIN sc_workspace_join_request r "
                + "ON r.account_id=a.id AND r.workspace_id=a.workspace_id WHERE a.id=?",(r,n)->new Member(r.getLong("id"),r.getString("account_id"),r.getString("username"),
                Objects.requireNonNullElse(r.getString("nickname"),r.getString("username")),r.getString("email"),r.getString("role"),r.getObject("workspace_id",Long.class),r.getBoolean("enabled"),membership(r.getString("status")),r.getLong("created_at")),targetId);
        if(rows.size()!=1) throw missingAccount();
        return rows.getFirst();
    }

    public Map<String,Object> overview(Actor supplied) {
        requireAdmin(supplied);
        var result=new LinkedHashMap<String,Object>();
        long workspaces=count("SELECT COUNT(*) FROM sc_workspace"), enabled=count("SELECT COUNT(*) FROM sc_workspace WHERE enabled=TRUE");
        long users=count("SELECT COUNT(*) FROM sc_account WHERE role='USER'"), active=count("SELECT COUNT(*) FROM sc_account a JOIN sc_workspace w ON w.id=a.workspace_id WHERE a.role='USER' AND a.enabled=TRUE AND w.enabled=TRUE");
        long disabled=users-active, admins=count("SELECT COUNT(*) FROM sc_account WHERE role='TENANT_ADMIN'");
        long pending=count("SELECT COUNT(*) FROM sc_workspace_join_request WHERE status='PENDING'");
        result.put("generatedAt",clock.millis()); result.put("timeZone","Asia/Shanghai"); result.put("workspaces",workspaces); result.put("members",users);
        result.put("workspaceAdmins",admins); result.put("storageSources",null); result.put("shares",null); result.put("loginLoggingEnabled",null);
        var statuses=new LinkedHashMap<String,Object>();
        statuses.put("enabledWorkspaces",enabled);statuses.put("disabledWorkspaces",workspaces-enabled);
        statuses.put("activeMembers",active);statuses.put("disabledMembers",disabled);statuses.put("pendingMemberships",pending);
        statuses.put("enabledStorages",null);statuses.put("expiredShares",null);statuses.put("runningAgentTasks",null);
        statuses.put("enabledSchedules",null);statuses.put("failedAgentTasks",null);statuses.put("failedSchedules",null);
        result.put("statuses",statuses);
        result.put("pendingAgentTasks",null); result.put("dailyLogins",List.of()); result.put("recentLogins",List.of());
        var loginSummary=new LinkedHashMap<String,Object>();loginSummary.put("successes24h",null);loginSummary.put("failures24h",null);result.put("loginSummary",loginSummary);
        var runtime=new LinkedHashMap<String,Object>();runtime.put("uptimeMs",ManagementFactoryHolder.uptime());runtime.put("databaseType","platform-owned SQL database");
        runtime.put("databaseLatencyMs",null);runtime.put("usedHeapBytes",ManagementFactoryHolder.usedHeap());runtime.put("maxHeapBytes",ManagementFactoryHolder.maxHeap());result.put("runtime",runtime);
        result.put("workspacePreview",jdbc.query("SELECT w.id,w.name,w.enabled,(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='USER') members FROM sc_workspace w ORDER BY w.id LIMIT 10",
                (r,n)->Map.of("id",r.getLong("id"),"tenantId",r.getLong("id"),"name",r.getString("name"),"enabled",r.getBoolean("enabled"),"members",r.getLong("members"))));
        result.put("aggregateAvailability",Map.of("identity","available","content","unavailable","intelligence","unavailable","loginLogs","unavailable"));
        return result;
    }

    public Map<String,Object> configuration(Actor supplied) {
        requireAdmin(supplied);
        return configurationValues();
    }

    /** Public bootstrap port contains only display and connection metadata from the explicit allowlist. */
    public Map<String,Object> publicSiteView() {
        Map<String,Object> all=configurationValues();var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("siteName","siteHomeName","icp","faviconUrl","siteHomeLogo","siteHomeLogoLink","siteHomeLogoTargetMode","frontDomain","forceBackendAddress","siteAdminLogoTargetMode","siteAdminVersionOpenChangeLog"))
            if(all.containsKey(key)) result.put(key,all.get(key));
        return result;
    }

    private Map<String,Object> configurationValues() {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("siteName",jdbc.queryForObject("SELECT site_name FROM sc_platform_settings WHERE id=1",String.class));
        jdbc.query("SELECT setting_key,setting_value FROM sc_platform_admin_setting",r->{String key=r.getString(1);if(SITE_KEYS.contains(key)||VIEW_KEYS.contains(key))data.put(key,readSetting(key,r.getString(2)));});
        data.putIfAbsent("siteHomeName",data.get("siteName")); data.putIfAbsent("maxFileUploads",10);
        data.putIfAbsent("siteAdminVersionOpenChangeLog",false);
        return data;
    }

    public void updateConfiguration(Actor supplied, Map<String,Object> input, boolean view) {
        requireAdmin(supplied);
        if(input==null || input.isEmpty()) throw invalid("设置内容不能为空");
        var allowed=view?VIEW_KEYS:SITE_AND_VIEW_KEYS;
        transactions.executeWithoutResult(status->{requireAdmin(supplied);for(var entry:input.entrySet()){
            if(!allowed.contains(entry.getKey())) continue;
            String value=settingValue(entry.getKey(),entry.getValue());
            if("siteName".equals(entry.getKey())) jdbc.update("UPDATE sc_platform_settings SET site_name=? WHERE id=1",value);
            else {
                int updated=jdbc.update("UPDATE sc_platform_admin_setting SET setting_value=? WHERE setting_key=?",value,entry.getKey());
                if(updated==0) try { jdbc.update("INSERT INTO sc_platform_admin_setting(setting_key,setting_value) VALUES(?,?)",entry.getKey(),value); }
                catch(DuplicateKeyException concurrent){jdbc.update("UPDATE sc_platform_admin_setting SET setting_value=? WHERE setting_key=?",value,entry.getKey());}
            }
        }});
    }

    private Actor requireAdmin(Actor supplied) { Actor current=fresh(supplied); if(current.role()!=Role.ADMIN || current.workspaceId()!=null) throw forbidden(); return current; }
    private Actor fresh(Actor supplied) {
        if(supplied==null||supplied.id()<1) throw unauthorized();
        List<Actor> rows=jdbc.query("SELECT id,account_id,username,email,role,workspace_id FROM sc_account WHERE id=? AND enabled=TRUE",(r,n)->new Actor(r.getLong("id"),r.getString("account_id"),r.getString("username"),r.getString("email"),Role.valueOf(r.getString("role")),r.getObject("workspace_id",Long.class)),supplied.id());
        if(rows.size()!=1) throw unauthorized(); Actor current=rows.getFirst();
        if(current.role()!=supplied.role()||!Objects.equals(current.workspaceId(),supplied.workspaceId())) throw forbidden();
        if(current.workspaceId()!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM sc_workspace WHERE id=?",Boolean.class,current.workspaceId()))) throw forbidden();
        return current;
    }
    private Workspace workspaceById(long id) { return workspacesForTransaction(id); }
    private Workspace workspacesForTransaction(long id) {
        return jdbc.queryForObject("SELECT w.id,w.name,w.enabled,w.created_at,(SELECT a.account_id FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN' ORDER BY a.id LIMIT 1) owner_id,"
                + "(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='USER') members,(SELECT COUNT(*) FROM sc_account a WHERE a.workspace_id=w.id AND a.role='TENANT_ADMIN') admins,"
                + "(SELECT COUNT(*) FROM sc_workspace_join_request r WHERE r.workspace_id=w.id AND r.status='PENDING') pending FROM sc_workspace w WHERE w.id=?",
                (r,n)->new Workspace(r.getLong("id"),r.getString("name"),r.getString("owner_id"),r.getLong("members"),r.getLong("admins"),r.getLong("pending"),null,r.getBoolean("enabled"),r.getLong("created_at")),id);
    }
    private long count(String sql){return Objects.requireNonNullElse(jdbc.queryForObject(sql,Long.class),0L);}
    private static void page(int page,int size){if(page<1||page>10_000||size<1||size>100||(long)(page-1)*size>100_000)throw invalid("分页参数无效");}
    private static String safeSearch(String value){if(value==null||value.isBlank())return "";return text(value,100,"搜索内容不能超过 100 字符");}
    private static String escapeLike(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
    private static String membership(String status){return status==null||"APPROVED".equals(status)?"ACTIVE":status;}
    private static String text(String value,int maximum,String message){if(value==null||value.isBlank()||value.length()>maximum+20||value.chars().anyMatch(Character::isISOControl))throw invalid(message);String normalized=Normalizer.normalize(value.trim(),Normalizer.Form.NFC);if(normalized.isBlank()||normalized.length()>maximum)throw invalid(message);return normalized;}
    private static String normalizeUsername(String value){String result=text(value,64,"用户名须为 3 至 64 字符").toLowerCase(Locale.ROOT);if(result.length()<3||!result.matches("[\\p{L}\\p{N}_.-]+")||result.matches("sc[0-9a-f]+"))throw invalid("用户名格式不正确");return result;}
    private static String normalizeEmail(String value){String raw=text(value,254,"请填写有效邮箱地址").toLowerCase(Locale.ROOT);int at=raw.indexOf('@');if(at<1||at!=raw.lastIndexOf('@')||at>64)throw invalid("请填写有效邮箱地址");String local=raw.substring(0,at),domain;try{domain=IDN.toASCII(raw.substring(at+1),IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);}catch(IllegalArgumentException e){throw invalid("请填写有效邮箱地址");}if(!local.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+")||local.startsWith(".")||local.endsWith(".")||local.contains("..")||domain.indexOf('.')<1||domain.endsWith(".")||domain.contains(".."))throw invalid("请填写有效邮箱地址");return local+"@"+domain;}
    private static String settingValue(String key,Object value){if(value==null)throw invalid("设置值不能为空");String result=String.valueOf(value);int max=key.equals("siteHomeLogo")?4000:500;if(result.length()>max||result.chars().anyMatch(Character::isISOControl))throw invalid("设置值长度或格式无效");if((key.equals("faviconUrl")||key.equals("frontDomain")||key.equals("forceBackendAddress"))&&!result.isEmpty()&&!result.matches("https?://[^\\s<>]{1,450}"))throw invalid("地址必须使用 HTTP 或 HTTPS");if(key.equals("siteAdminVersionOpenChangeLog")&&!result.equalsIgnoreCase("true")&&!result.equalsIgnoreCase("false"))throw invalid("布尔设置格式无效");if(key.equals("maxFileUploads")){try{int count=Integer.parseInt(result);if(count<1||count>1000)throw invalid("最大上传数须为 1 至 1000");}catch(NumberFormatException bad){throw invalid("最大上传数格式无效");}}return result;}
    private static Object readSetting(String key,String value){if(key.equals("siteAdminVersionOpenChangeLog"))return Boolean.parseBoolean(value);if(key.equals("maxFileUploads")){try{return Integer.valueOf(value);}catch(NumberFormatException ignored){return 10;}}return value;}
    private static ApplicationFault invalid(String message){return new ApplicationFault("41000",400,message);}
    private static ApplicationFault unauthorized(){return new ApplicationFault("42000",401,"请重新登录");}
    private static ApplicationFault forbidden(){return new ApplicationFault("30000",403,"当前账号不能执行此管理操作");}
    private static ApplicationFault missingWorkspace(){return new ApplicationFault("WORKSPACE_NOT_FOUND",404,"工作空间不存在或已停用");}
    private static ApplicationFault missingAccount(){return new ApplicationFault("ACCOUNT_NOT_FOUND",404,"账号不存在");}
    private static ApplicationFault duplicateAccount(){return new ApplicationFault("ACCOUNT_ALREADY_EXISTS",409,"用户名或邮箱已被使用");}
    private static final java.util.Set<String> SITE_KEYS=java.util.Set.of("siteName","siteHomeName","icp","maxFileUploads","faviconUrl","siteHomeLogo","siteHomeLogoLink","siteHomeLogoTargetMode","forceBackendAddress","frontDomain");
    private static final java.util.Set<String> VIEW_KEYS=java.util.Set.of("siteAdminLogoTargetMode","siteAdminVersionOpenChangeLog");
    private static final java.util.Set<String> SITE_AND_VIEW_KEYS=java.util.Set.of("siteName","siteHomeName","icp","maxFileUploads","faviconUrl","siteHomeLogo","siteHomeLogoLink","siteHomeLogoTargetMode","forceBackendAddress","frontDomain","siteAdminLogoTargetMode","siteAdminVersionOpenChangeLog");
    private static final class ManagementFactoryHolder {
        private static final long START=System.nanoTime();
        static long uptime(){return Math.max(0,(System.nanoTime()-START)/1_000_000L);}
        static long usedHeap(){var runtime=Runtime.getRuntime();return Math.max(0,runtime.totalMemory()-runtime.freeMemory());}
        static long maxHeap(){return Runtime.getRuntime().maxMemory();}
    }
}
