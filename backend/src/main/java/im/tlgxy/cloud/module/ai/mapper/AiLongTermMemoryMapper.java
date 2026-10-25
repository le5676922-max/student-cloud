package im.tlgxy.cloud.module.ai.mapper;

import im.tlgxy.cloud.module.ai.model.memory.AiMemoryProfile;
import org.apache.ibatis.annotations.*;

/** All reads and writes require the account key, including compare-and-set updates. */
@Mapper
public interface AiLongTermMemoryMapper {
    @Select("SELECT owner_user_id AS ownerUserId, enabled, entries_json AS entriesJson, version FROM ai_long_term_memory WHERE owner_user_id = #{owner}")
    AiMemoryProfile find(@Param("owner") int owner);

    @Insert("INSERT INTO ai_long_term_memory (owner_user_id, enabled, entries_json, version, updated_at) VALUES (#{owner}, 1, '[]', 0, CURRENT_TIMESTAMP)")
    int insertEmpty(@Param("owner") int owner);

    @Update("UPDATE ai_long_term_memory SET enabled = #{enabled}, entries_json = #{entries}, version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE owner_user_id = #{owner} AND version = #{version}")
    int compareAndSet(@Param("owner") int owner, @Param("version") long version,
                      @Param("enabled") boolean enabled, @Param("entries") String entries);

    @Delete("DELETE FROM ai_long_term_memory WHERE owner_user_id = #{owner}")
    int deleteOwned(@Param("owner") int owner);
}
