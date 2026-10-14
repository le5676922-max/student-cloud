package im.tlgxy.cloud.module.storage.service;

import im.tlgxy.cloud.module.storage.model.param.*;
import im.tlgxy.cloud.module.storage.service.base.AbstractS3BaseFileService;
import im.tlgxy.cloud.module.storage.service.impl.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

import static org.mockito.Mockito.*;

class S3InitializationNoCorsTest {
    @Test void allProvidersBuildClientsWithoutAnyRemoteMutationEvenWhenSavedCorsIsNonempty() {
        S3Client client = mock(S3Client.class);
        S3ClientBuilder builder = mock(S3ClientBuilder.class, RETURNS_SELF); when(builder.build()).thenReturn(client);
        S3Presigner presigner = mock(S3Presigner.class);
        S3Presigner.Builder presignerBuilder = mock(S3Presigner.Builder.class, RETURNS_SELF); when(presignerBuilder.build()).thenReturn(presigner);
        try (var s3 = mockStatic(S3Client.class); var signing = mockStatic(S3Presigner.class)) {
            s3.when(S3Client::builder).thenReturn(builder); signing.when(S3Presigner::builder).thenReturn(presignerBuilder);
            var services = List.of(new S3ServiceImpl(), new AliyunServiceImpl(), new MinIOServiceImpl(), new HuaweiServiceImpl(), new TencentServiceImpl(), new QiniuServiceImpl());
            var parameters = List.of(new S3Param(), new AliyunParam(), new MinIOParam(), new HuaweiParam(), new TencentParam(), new QiniuParam());
            for (int index = 0; index < services.size(); index++) {
                S3BaseParam param = parameters.get(index);
                ReflectionTestUtils.setField(param, "endPoint", "https://objects.example.test");
                ReflectionTestUtils.setField(param, "accessKey", "synthetic-access"); ReflectionTestUtils.setField(param, "secretKey", "synthetic-secret");
                ReflectionTestUtils.setField(param, "corsConfigList", "[{\"allowedMethods\":[\"PUT\"],\"allowedOrigins\":[\"*\"]}]");
                if (param instanceof S3Param || param instanceof MinIOParam) ReflectionTestUtils.setField(param, "region", "test-region");
                if (param instanceof S3Param) ReflectionTestUtils.setField(param, "pathStyle", "path-style");
                AbstractS3BaseFileService<?> service = services.get(index); ReflectionTestUtils.setField(service, "param", param);
                service.init();
            }
            verifyNoInteractions(client);
        }
    }
}
