package io.studentcloud.account;

import io.studentcloud.common.ApplicationFault;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Email ownership, account creation and membership assignment; never accepts a public ADMIN role. */
public final class PublicRegistrationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final EmailCodeService codes;
    private final WorkspaceEnrollmentService enrollment;
    private final boolean registrationEnabled,resetEnabled;
    private final PasswordHasher passwords=new PasswordHasher();

    public PublicRegistrationService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,EmailCodeService codes,
            WorkspaceEnrollmentService enrollment,boolean registrationEnabled,boolean resetEnabled) {
        this.jdbc=Objects.requireNonNull(jdbc);this.tx=new TransactionTemplate(manager);this.clock=Objects.requireNonNull(clock);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);this.tx.setTimeout(15);
        this.codes=Objects.requireNonNull(codes);this.enrollment=Objects.requireNonNull(enrollment);
        this.registrationEnabled=registrationEnabled;this.resetEnabled=resetEnabled;
    }

    public boolean registrationAvailable() {return registrationEnabled && installed() && codes.available();}
    public boolean resetAvailable() {return resetEnabled && installed() && codes.available();}
    public boolean mailAvailable() {return codes.available();}

    public void requestRegistrationCode(String email) {
        requireAvailable(registrationAvailable());codes.request(email,EmailPurpose.REGISTER);
    }
    public void requestResetCode(String email) {
        requireAvailable(resetAvailable());
        String normalized=EmailCodeService.normalizeEmail(email);
        // Same public acknowledgement whether a mailbox owns an account or not.
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE email=?",Long.class,normalized);
        codes.requestPasswordReset(normalized,count!=null && count==1);
    }

    public record RegistrationInput(String email,String emailCode,String password,String confirmPassword,String nickname,
                                    String registrationType,String workspaceName,String joinType,String joinValue) { }
    public record RegistrationResult(String accountId,String email,String role,long tenantId,String tenantName,String status,String message) { }

    public RegistrationResult register(RegistrationInput input) {
        requireAvailable(registrationAvailable());
        if(input==null) throw invalid("请完整填写注册信息");
        String email=EmailCodeService.normalizeEmail(input.email());
        requirePassword(input.password(),input.confirmPassword());
        Role role;
        if("TENANT_ADMIN".equals(input.registrationType())) role=Role.TENANT_ADMIN;
        else if("USER".equals(input.registrationType())) role=Role.USER;
        else throw invalid("请选择空间管理员或成员，不能注册全局管理员");
        String nickname=text(input.nickname(),100,true);
        if(role==Role.TENANT_ADMIN) text(input.workspaceName(),80,false);
        else {
            if(!"NAME".equals(input.joinType()) && !"INVITE".equals(input.joinType())) throw invalid("请选择邀请码或空间名称加入");
            text(input.joinValue(),80,false);
        }
        var proof=codes.verify(email,EmailPurpose.REGISTER,input.emailCode());
        String encoded=passwords.hash(input.password());
        String entropy=UUID.randomUUID().toString().replace("-","");
        String accountId="SC"+entropy.toUpperCase(Locale.ROOT),username="u_"+entropy;
        try {
            return tx.execute(status->{
                requireAvailable(registrationAvailable());
                codes.consume(proof);
                if(Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account WHERE email=?",Long.class,email))!=0)
                    throw new ApplicationFault("ACCOUNT_ALREADY_EXISTS",409,"该邮箱已注册，请登录或找回密码");
                var key=new GeneratedKeyHolder();
                jdbc.update(connection->{
                    PreparedStatement statement=connection.prepareStatement("INSERT INTO sc_account(account_id,username,email,password_hash,role,enabled,session_version,created_at) VALUES(?,?,?,?,?,FALSE,1,?)",Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1,accountId);statement.setString(2,username);statement.setString(3,email);statement.setString(4,encoded);
                    statement.setString(5,role.name());statement.setLong(6,clock.millis());return statement;
                },key);
                long id=Objects.requireNonNull(key.getKey()).longValue();
                var target=role==Role.TENANT_ADMIN ? enrollment.newWorkspace(id,input.workspaceName())
                        : enrollment.enrollMember(input.joinType(),input.joinValue(),id);
                jdbc.update("INSERT INTO sc_public_profile(account_id,nickname,email_verified_at) VALUES(?,?,?)",id,nickname.isBlank()?accountId:nickname,clock.millis());
                String message=target.pending()?"注册成功，加入申请等待空间管理员审核":"注册成功，可以使用邮箱或账号ID登录";
                return new RegistrationResult(accountId,email,role.name(),target.workspaceId(),target.workspaceName(),target.pending()?"PENDING":"ACTIVE",message);
            });
        }catch(DuplicateKeyException conflict){throw new ApplicationFault("ACCOUNT_ALREADY_EXISTS",409,"邮箱或账号已被使用，请核对后登录或重试");}
    }

    public void resetPassword(String email,String code,String password,String confirmPassword) {
        requireAvailable(resetAvailable());
        String normalized=EmailCodeService.normalizeEmail(email);
        requirePassword(password,confirmPassword);
        var proof=codes.verify(normalized,EmailPurpose.RESET_PASSWORD,code);
        String encoded=passwords.hash(password);
        tx.executeWithoutResult(status->{
            requireAvailable(resetAvailable());
            codes.consume(proof);
            List<Long> ids=jdbc.queryForList("SELECT id FROM sc_account WHERE email=? FOR UPDATE",Long.class,normalized);
            if(ids.size()!=1) throw invalid("验证码无效或操作已失效，请重新申请");
            Long version=jdbc.queryForObject("SELECT session_version FROM sc_account WHERE id=?",Long.class,ids.getFirst());
            if(version==null || version<1 || version==Long.MAX_VALUE) throw invalid("账号状态需要管理员处理");
            jdbc.update("UPDATE sc_account SET password_hash=?,session_version=session_version+1 WHERE id=?",encoded,ids.getFirst());
            jdbc.update("DELETE FROM sc_session WHERE account_id=?",ids.getFirst());
            jdbc.update("UPDATE sc_public_profile SET email_verified_at=? WHERE account_id=?",clock.millis(),ids.getFirst());
            // Resetting a password never approves a pending member or reenables a disabled account.
        });
    }

    public String nickname(long id,String fallback) {
        List<String> names=jdbc.queryForList("SELECT nickname FROM sc_public_profile WHERE account_id=?",String.class,id);
        return names.isEmpty()?fallback:names.getFirst();
    }

    public java.util.Map<String,String> nicknamesByAccountIds(List<String> accountIds){
        if(accountIds.isEmpty())return java.util.Map.of();
        if(accountIds.size()>200)throw invalid("本次资料查询数量过多");
        String placeholders=String.join(",",java.util.Collections.nCopies(accountIds.size(),"?"));
        var names=new java.util.HashMap<String,String>();
        jdbc.query("SELECT a.account_id,p.nickname FROM sc_public_profile p JOIN sc_account a ON a.id=p.account_id WHERE a.account_id IN ("+placeholders+")",
                row->{names.put(row.getString("account_id"),row.getString("nickname"));},accountIds.toArray());
        return java.util.Map.copyOf(names);
    }

    private boolean installed() {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT installed FROM sc_platform_settings WHERE id=1",Boolean.class));}
    private static void requireAvailable(boolean value) {if(!value) throw new ApplicationFault("MAIL_UNAVAILABLE",503,"邮箱服务尚未启用，请联系管理员配置SMTP");}
    private static void requirePassword(String password,String confirmation) {
        if(!PasswordHasher.validNewPassword(password) || password.isBlank()) throw invalid("密码须为8至128字符");
        if(!password.equals(confirmation)) throw invalid("两次输入的密码不一致");
    }
    private static String text(String value,int max,boolean optional) {
        String result=value==null?"":value.strip();
        if(!optional && result.isBlank() || result.length()>max || result.chars().anyMatch(Character::isISOControl)) throw invalid("注册信息格式不正确或过长");
        return result;
    }
    private static ApplicationFault invalid(String message){return new ApplicationFault("41000",400,message);}
}
