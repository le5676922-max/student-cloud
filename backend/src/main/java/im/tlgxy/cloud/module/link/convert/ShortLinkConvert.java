package im.tlgxy.cloud.module.link.convert;

import im.tlgxy.cloud.module.link.model.entity.ShortLink;
import im.tlgxy.cloud.module.storage.model.entity.StorageSource;
import im.tlgxy.cloud.module.link.model.request.ShortLinkResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.springframework.stereotype.Component;

/**
 * 短链实体转换器
 * <p>
 * 使用 MapStruct 框架实现 {@link ShortLink} 实体与 {@link ShortLinkResult} 结果 DTO 之间的字段映射。
 * 该接口会在编译时自动生成实现类，完成高效的字段拷贝工作。
 * <p>
 * 配置说明：
 * <ul>
 *   <li>componentModel = "spring"：生成的实现类注册为 Spring Bean</li>
 *   <li>unmappedTargetPolicy = IGNORE：忽略未映射的目标字段，不报错</li>
 * </ul>
 * <p>
 * 字段映射关系：
 * <ul>
 *   <li>shortLink.id -> id</li>
 *   <li>storageSource.name -> storageName</li>
 *   <li>storageSource.type -> storageType</li>
 * </ul>
 *
 * @author zhaojun
 */
@Component
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ShortLinkConvert {

    /**
     * 将短链实体和存储源实体转换为短链结果 DTO
     * <p>
     * 合并两个数据源的字段，生成前端展示所需的完整短链信息。
     *
     * @param   shortLink
     *          短链实体，提供短链的基本信息（ID、key、URL、时间等）
     *
     * @param   storageSource
     *          存储源实体，提供存储源的名称和类型信息
     *
     * @return  合并后的短链结果 DTO，用于前端列表展示
     */
    @Mapping(source = "shortLink.id", target = "id")
    @Mapping(source = "storageSource.name", target = "storageName")
    @Mapping(source = "storageSource.type", target = "storageType")
    ShortLinkResult entityToResultList(ShortLink shortLink, StorageSource storageSource);

}