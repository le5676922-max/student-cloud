package im.tlgxy.cloud.module.storage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real mapper's portable SQL against synthetic SQLite rows, including legacy root grants. */
class StorageSourceVisibilitySqlTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private String sql;
    @BeforeEach void database() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("visibility.db"));source.setDriverClassName("org.sqlite.JDBC");jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE `user`(id INTEGER PRIMARY KEY,tenant_id INTEGER,role TEXT,enable INTEGER)");
        jdbc.execute("CREATE TABLE storage_source(id INTEGER PRIMARY KEY,name TEXT,`key` TEXT,type TEXT,search_enable INTEGER,default_switch_to_img_mode INTEGER,tenant_id INTEGER,owner_user_id INTEGER,enable INTEGER,order_num INTEGER)");
        jdbc.execute("CREATE TABLE user_storage_source(user_id INTEGER,storage_source_id INTEGER,root_path TEXT,enable INTEGER)");
        jdbc.update("INSERT INTO `user` VALUES(10,9,'USER',1),(11,9,'TENANT_ADMIN',1),(12,8,'USER',1)");
        for(int id=1;id<=6;id++) jdbc.update("INSERT INTO storage_source VALUES(?,?,'key','local',1,0,?,11,1,0)",id,"source-"+id,id==6?8:9);
        var stream = getClass().getClassLoader().getResourceAsStream("mapper/StorageSourceMapper.xml");
        assertNotNull(stream);
        String xml; try(stream) {xml=new String(stream.readAllBytes(), StandardCharsets.UTF_8);}
        int start=xml.indexOf("<select id=\"findUserEnableList\"");
        sql=xml.substring(xml.indexOf('>',start)+1,xml.indexOf("</select>",start)).replace("#{userId}","?");
    }
    private List<String> visible(int user) {return jdbc.query(sql,(rs,n)->rs.getString("name"),user);}
    @Test void legacySharedRootsOtherMembersRootsAndCrossWorkspaceGrantsDoNotAppear() {
        for(var grant:List.of(new Object[]{1,"/"},new Object[]{2,"/members/u11"},new Object[]{3,"/members/u100"},new Object[]{4,"/members/u10"},new Object[]{5,"/team/members/u10/courses"},new Object[]{6,"/members/u10"}))
            jdbc.update("INSERT INTO user_storage_source VALUES(10,?,?,1)",grant[0],grant[1]);
        assertEquals(List.of("source-5","source-4"),visible(10));
    }
    @Test void managerCanSeeOnlyOwnTenantRootsAndDisabledGrantNeverAppears() {
        jdbc.update("INSERT INTO user_storage_source VALUES(11,1,'/',1),(11,2,'/',0),(11,6,'/',1)");
        assertEquals(List.of("source-1"),visible(11));
        jdbc.update("UPDATE `user` SET enable=0 WHERE id=11"); assertTrue(visible(11).isEmpty());
    }
}
