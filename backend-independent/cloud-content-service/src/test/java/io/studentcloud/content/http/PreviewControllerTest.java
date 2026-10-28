package io.studentcloud.content.http;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.application.MountService;
import io.studentcloud.runtime.http.AuthenticationFilter;
import io.studentcloud.runtime.http.HttpFailures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PreviewControllerTest {
    @TempDir Path temporary;
    private JdbcTemplate jdbc;
    private Path own;
    private MockMvc mvc;
    private PreviewController controller;
    private Actor member;
    private MountService mounts;

    @BeforeEach void setup() throws Exception {
        Path managed=Files.createDirectory(temporary.resolve("managed"));
        Path mounted=Files.createDirectory(managed.resolve("drive"));
        own=Files.createDirectories(mounted.resolve("members/u3"));
        Files.writeString(mounted.resolve("outside.txt"),"other account data");
        var database=new JdbcDataSource();
        database.setURL("jdbc:h2:mem:preview_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/independent-drives-schema.sql")).execute(database);
        jdbc=new JdbcTemplate(database);
        var admin=new Actor(1,"a1","admin","admin@example.test",Role.ADMIN,null);
        member=new Actor(3,"a3","member","member@example.test",Role.USER,1L);
        AccountDirectory accounts=new AccountDirectory(){public Actor account(long id){return id==1?admin:member;}public boolean workspaceEnabled(long id){return id==1;}};
        mounts=new MountService(jdbc,new DataSourceTransactionManager(database),Clock.systemUTC(),managed,accounts);
        var mount=mounts.createLocal(admin,"个人云盘","private-drive",mounted,1L);
        jdbc.update("INSERT INTO sc_drive_grant(user_id,mount_id,root_path,permissions,enabled,revision,created_at) VALUES(3,?,'/members/u3','list,read',TRUE,1,1)",mount.id());
        controller=new PreviewController(mounts);
        mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new HttpFailures())
                .addFilters(new AuthenticationFilter(token->{if(!"member".equals(token))throw new ApplicationFault("42000",401,"请先登录");return member;},new ObjectMapper())).build();
    }
    @Test void authenticatedUtf8TextAndMarkdownStayPlainTextWithNoStoreAndNoSniff() throws Exception {
        String text="# 资料\n<img src=\"https://external.invalid/x\" onerror=\"alert(1)\">";
        Files.writeString(own.resolve("note.md"),text);
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/note.md").header("Authorization","Bearer member"))
                .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(text)).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Security-Policy",org.hamcrest.Matchers.containsString("sandbox")));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/note.md")).andExpect(status().isUnauthorized());
    }
    @Test void imagesAndPdfAreCheckedByContentAndReturnedWithAnExplicitSafeType() throws Exception {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j0ZkAAAAASUVORK5CYII=");
        Files.write(own.resolve("pixel.PNG"),png);
        Files.writeString(own.resolve("document.pdf"),"%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF");
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/pixel.PNG").header("Authorization","Bearer member"))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(content().bytes(png));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/document.pdf").header("Authorization","Bearer member"))
                .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }
    @Test void executableBinariesSpoofedImagesAndInvalidUtf8AreRejected() throws Exception {
        for(String name:java.util.List.of("binary.exe","macro.docm","spoof.png","spoof.pdf","fake.mp3","fake.mp4","fake.docx")){
            Files.writeString(own.resolve(name),"<script>alert(1)</script>");
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+name).header("Authorization","Bearer member"))
                    .andExpect(status().isUnsupportedMediaType());
        }
        Files.write(own.resolve("not-utf8.txt"),new byte[]{(byte)0xc3,(byte)0x28});
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/not-utf8.txt").header("Authorization","Bearer member"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void markupCodeAndCsvAreServedAsPlainTextRatherThanExecuted() throws Exception {
        String text="<script>alert('not executed')</script>\n中文,<img src=https://outside.invalid/test>";
        for(String name:java.util.List.of("page.html","drawing.svg","script.js","module.ts","sample.vue","data.json","config.yaml","Example.java","data.csv","values.tsv","Dockerfile","README")){
            Files.writeString(own.resolve(name),text);
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+name).header("Authorization","Bearer member"))
                    .andExpect(status().isOk()).andExpect(content().contentType("text/plain;charset=UTF-8"))
                    .andExpect(content().string(text)).andExpect(header().string("X-Content-Type-Options","nosniff"));
        }
    }

    @Test void supportedMediaArePrivateBinaryResponsesWithExplicitContainerTypes() throws Exception {
        var samples=new java.util.LinkedHashMap<String,byte[]>();
        samples.put("track.mp3",new byte[]{(byte)255,(byte)251,(byte)144,100});
        samples.put("track.m4a",new byte[]{0,0,0,16,'f','t','y','p','M','4','A',' ',0,0,0,0});
        samples.put("track.aac",new byte[]{(byte)255,(byte)241,80,(byte)128,0,(byte)255,(byte)252});
        byte[] wav=new byte[44];System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,wav,0,4);System.arraycopy("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,wav,8,4);samples.put("track.wav",wav);
        byte[] ogg=new byte[27];System.arraycopy("OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,ogg,0,4);samples.put("track.ogg",ogg);samples.put("movie.ogv",ogg);
        byte[] flac=new byte[42];System.arraycopy("fLaC".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,flac,0,4);flac[7]=34;samples.put("track.flac",flac);
        samples.put("movie.mp4",new byte[]{0,0,0,16,'f','t','y','p','i','s','o','m',0,0,0,0});
        samples.put("movie.webm",new byte[]{26,69,(byte)223,(byte)163,(byte)255,66,(byte)130,(byte)132,'w','e','b','m'});
        for(var entry:samples.entrySet()){
            Files.write(own.resolve(entry.getKey()),entry.getValue());
            var type=PreviewContentPolicy.format(new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/"+entry.getKey()),entry.getKey(),false,entry.getValue().length,java.time.Instant.EPOCH));
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+entry.getKey()).header("Authorization","Bearer member"))
                    .andExpect(status().isOk()).andExpect(content().contentType(type.mediaType())).andExpect(content().bytes(entry.getValue()))
                    .andExpect(header().string("Cache-Control","no-store"));
        }
        // A header from a different container never becomes playable simply by renaming its extension.
        Files.write(own.resolve("wrong.wav"),samples.get("movie.mp4"));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/wrong.wav").header("Authorization","Bearer member"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void officeContainersAreValidatedAndRemainAuthenticatedBytes() throws Exception {
        for(String extension:java.util.List.of("docx","xlsx","pptx")){
            byte[] bytes=office(extension,java.util.Map.of());Files.write(own.resolve("sample."+extension),bytes);
            var format=PreviewContentPolicy.format(new io.studentcloud.drive.FileRecord(io.studentcloud.drive.DrivePath.of("/sample."+extension),"sample."+extension,false,bytes.length,java.time.Instant.EPOCH));
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/sample."+extension).header("Authorization","Bearer member"))
                    .andExpect(status().isOk()).andExpect(content().contentType(format.mediaType())).andExpect(content().bytes(bytes));
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/sample."+extension)).andExpect(status().isUnauthorized());
        }
    }

    @Test void basicModelsUsePrivateOctetStreamsAndRejectMaterialReferences() throws Exception {
        String object="v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
        Files.writeString(own.resolve("triangle.obj"),object);
        Files.write(own.resolve("triangle.stl"),BasicModelPreviewPolicyTest.binary(1));
        for(String file:java.util.List.of("triangle.obj","triangle.stl")){
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+file).header("Authorization","Bearer member"))
                    .andExpect(status().isOk()).andExpect(content().contentType("application/octet-stream"))
                    .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+file)).andExpect(status().isUnauthorized());
        }
        Files.writeString(own.resolve("linked.obj"),object+"mtllib https://outside.invalid/model.mtl\n");
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/linked.obj").header("Authorization","Bearer member"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void officeZipBombMacrosTraversalAndExternalEntitiesAreRejected() throws Exception {
        byte[] bomb=new byte[4*1024*1024+1];
        var invalid=new java.util.LinkedHashMap<String,byte[]>();
        invalid.put("expansion.docx",office("docx",java.util.Map.of("word/media/bomb.bin",bomb)));
        invalid.put("macro.docx",office("docx",java.util.Map.of("word/vbaProject.bin",new byte[]{1})));
        invalid.put("traversal.docx",office("docx",java.util.Map.of("../hidden.xml",new byte[]{1})));
        invalid.put("entities.docx",office("docx",java.util.Map.of("word/document.xml","<!DOCTYPE document [<!ENTITY ex SYSTEM 'https://outside.invalid/never-fetch'>]><document xmlns='http://schemas.openxmlformats.org/wordprocessingml/2006/main'>&ex;</document>".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        invalid.put("mismatched.docx",office("xlsx",java.util.Map.of()));
        for(var entry:invalid.entrySet()){
            Files.write(own.resolve(entry.getKey()),entry.getValue());
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+entry.getKey()).header("Authorization","Bearer member"))
                    .andExpect(entry.getKey().startsWith("expansion")?status().isPayloadTooLarge():status().isUnsupportedMediaType());
        }
        byte[] complete=office("docx",java.util.Map.of());Files.write(own.resolve("truncated.docx"),java.util.Arrays.copyOf(complete,complete.length-22));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/truncated.docx").header("Authorization","Bearer member"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test void permissionRevokedAfterReadingFailsBeforePrivateBytesAreWritten() throws Exception {
        Files.writeString(own.resolve("revoke.txt"),"must remain private");
        var once=new java.util.concurrent.atomic.AtomicBoolean();
        var guarded=(io.studentcloud.drive.application.DriveOperations)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class<?>[]{io.studentcloud.drive.application.DriveOperations.class},(proxy,method,args)->{
                    try{
                        Object result=method.invoke(mounts,args);
                        if(method.getName().equals("readWithMetadata")&&once.compareAndSet(false,true)){
                            var handle=(io.studentcloud.drive.application.ReadHandle)result;
                            return new io.studentcloud.drive.application.ReadHandle(handle.metadata(),new java.io.FilterInputStream(handle.input()){
                                @Override public void close()throws java.io.IOException{super.close();jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");}
                            });
                        }
                        return result;
                    }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                });
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute(Actor.class.getName(),member);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        var failure=org.junit.jupiter.api.Assertions.assertThrows(ApplicationFault.class,()->new PreviewController(guarded).preview("private-drive","/revoke.txt",request,response));
        org.junit.jupiter.api.Assertions.assertEquals(403,failure.httpStatus());
        org.junit.jupiter.api.Assertions.assertEquals(0,response.getContentAsByteArray().length);
    }

    private static byte[] office(String extension,java.util.Map<String,byte[]> overrides)throws Exception{
        String main=extension.equals("docx")?"word/document.xml":extension.equals("xlsx")?"xl/workbook.xml":"ppt/presentation.xml";
        String namespace=extension.equals("docx")?"http://schemas.openxmlformats.org/wordprocessingml/2006/main":extension.equals("xlsx")?"http://schemas.openxmlformats.org/spreadsheetml/2006/main":"http://schemas.openxmlformats.org/presentationml/2006/main";
        String root=extension.equals("docx")?"document":extension.equals("xlsx")?"workbook":"presentation";
        String contentType=extension.equals("docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":extension.equals("xlsx")?"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":"application/vnd.openxmlformats-officedocument.presentationml.presentation";
        var parts=new java.util.LinkedHashMap<String,byte[]>();
        parts.put("[Content_Types].xml",("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/"+main+"' ContentType='"+contentType+".main+xml'/></Types>").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        parts.put(main,("<"+root+" xmlns='"+namespace+"'/>").getBytes(java.nio.charset.StandardCharsets.UTF_8));parts.putAll(overrides);
        var output=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(output)){
            for(var part:parts.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(part.getKey()));zip.write(part.getValue());zip.closeEntry();}
        }
        return output.toByteArray();
    }
    @Test void fileBudgetsAreCheckedBeforeReadingLargeContent() throws Exception {
        for(var entry:java.util.Map.of("large.pdf",20L*1048576+1,"large.txt",1048577L,"large.docx",10L*1048576+1).entrySet()){
            try(var file=new java.io.RandomAccessFile(own.resolve(entry.getKey()).toFile(),"rw")){file.setLength(entry.getValue());}
            mvc.perform(get("/api/drives/private-drive/preview").param("path","/"+entry.getKey()).header("Authorization","Bearer member"))
                    .andExpect(status().isPayloadTooLarge());
        }
    }

    @Test void officeTotalExpansionAndEntryCountAreBoundedEvenWhenEachPartIsSmall() throws Exception {
        var expanded=new java.util.LinkedHashMap<String,byte[]>();
        byte[] block=new byte[4*1024*1024];
        for(int index=0;index<6;index++)expanded.put("word/media/part-"+index+".bin",block);
        Files.write(own.resolve("total.docx"),office("docx",expanded));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/total.docx").header("Authorization","Bearer member"))
                .andExpect(status().isPayloadTooLarge());
        var excessive=new java.util.LinkedHashMap<String,byte[]>();
        for(int index=0;index<1000;index++)excessive.put("word/part-"+index+".xml",new byte[0]);
        Files.write(own.resolve("many.docx"),office("docx",excessive));
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/many.docx").header("Authorization","Bearer member"))
                .andExpect(status().isUnsupportedMediaType());
    }
    @Test void personalRootAndFreshReadPermissionRemainMandatory() throws Exception {
        Files.writeString(own.resolve("own.txt"),"private");
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/outside.txt").header("Authorization","Bearer member"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/../outside.txt").header("Authorization","Bearer member"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE sc_drive_grant SET permissions='list',revision=revision+1");
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/own.txt").header("Authorization","Bearer member"))
                .andExpect(status().isForbidden());
    }

    @Test void slowResponseWritersRetainTheGlobalBudgetUntilOutputCompletes() throws Exception {
        Files.writeString(own.resolve("small.txt"),"private");
        var entered=new java.util.concurrent.CountDownLatch(2);
        var release=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<2;n++)futures.add(workers.submit(()->{
                var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAttribute(Actor.class.getName(),member);
                var output=new org.springframework.mock.web.MockHttpServletResponse(){
                    @Override public jakarta.servlet.ServletOutputStream getOutputStream(){return new jakarta.servlet.ServletOutputStream(){
                        @Override public boolean isReady(){return true;}
                        @Override public void setWriteListener(jakarta.servlet.WriteListener listener){}
                        @Override public void write(int value)throws java.io.IOException{block();}
                        @Override public void write(byte[] bytes,int offset,int length)throws java.io.IOException{block();}
                        private void block()throws java.io.IOException{entered.countDown();try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("test output timeout");}catch(InterruptedException stop){Thread.currentThread().interrupt();throw new java.io.IOException(stop);}}
                    };}
                };
                try{controller.preview("private-drive","/small.txt",request,output);}catch(Exception failure){throw new RuntimeException(failure);}
            }));
            try{
                org.junit.jupiter.api.Assertions.assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                mvc.perform(get("/api/drives/private-drive/preview").param("path","/small.txt").header("Authorization","Bearer member"))
                        .andExpect(status().isTooManyRequests());
            }finally{release.countDown();}
            for(var future:futures)future.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        mvc.perform(get("/api/drives/private-drive/preview").param("path","/small.txt").header("Authorization","Bearer member"))
                .andExpect(status().isOk());
    }
}
