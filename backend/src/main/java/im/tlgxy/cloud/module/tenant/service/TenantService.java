package im.tlgxy.cloud.module.tenant.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import im.tlgxy.cloud.core.exception.core.BizException;
import im.tlgxy.cloud.module.tenant.mapper.TenantMapper;
import im.tlgxy.cloud.module.tenant.model.entity.Tenant;
import im.tlgxy.cloud.module.tenant.model.request.SaveTenantRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantMapper tenantMapper;
    private final TenantSecurityService tenantSecurityService;

    public List<Tenant> listAll() {
        tenantSecurityService.requireSystemAdmin();
        return tenantMapper.selectList(Wrappers.<Tenant>lambdaQuery()
                .orderByAsc(Tenant::getId));
    }

    public Tenant save(SaveTenantRequest request) {
        tenantSecurityService.requireSystemAdmin();
        Long duplicateCount = tenantMapper.selectCount(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getName, request.getName().trim())
                .ne(request.getId() != null, Tenant::getId, request.getId()));
        if (duplicateCount > 0) {
            throw new BizException("工作空间名称已存在");
        }

        Tenant tenant = request.getId() == null ? new Tenant() : tenantMapper.selectById(request.getId());
        if (tenant == null) {
            throw new BizException("工作空间不存在");
        }
        tenant.setName(request.getName().trim());
        tenant.setEnable(request.getEnable() == null || request.getEnable());
        if (tenant.getId() == null) {
            tenantMapper.insert(tenant);
        } else {
            tenantMapper.updateById(tenant);
        }
        return tenant;
    }

    public Tenant getEnabledById(Integer tenantId) {
        Tenant tenant = tenantId == null ? null : tenantMapper.selectById(tenantId);
        if (tenant == null || !Boolean.TRUE.equals(tenant.getEnable())) {
            throw new BizException("工作空间不存在或已被停用");
        }
        return tenant;
    }

}
