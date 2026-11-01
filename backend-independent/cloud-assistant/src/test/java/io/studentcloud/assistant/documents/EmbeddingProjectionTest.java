package io.studentcloud.assistant.documents;

import io.studentcloud.common.ApplicationFault;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingProjectionTest {
    @Test void defaultOffMissingConfigurationAndNoOwnerConsentMakeZeroExternalRequests() {
        var calls=new AtomicInteger();
        EmbeddingProjection.Provider external=texts->{calls.incrementAndGet();return batch(List.of(new EmbeddingProjection.Vector(0,List.of(1d,2d))));};
        assertEquals("EXTERNAL_DISABLED",new EmbeddingProjection(false,"model","v1",2,external).embed(List.of("private"),true).reason());
        assertEquals("CONSENT_REQUIRED",new EmbeddingProjection(true,"model","v1",2,external).embed(List.of("private"),false).reason());
        assertEquals("PROVIDER_UNCONFIGURED",new EmbeddingProjection(true,"model","v1",2,null).embed(List.of("private"),true).reason());
        assertEquals(0,calls.get());
    }
    @Test void malformedModelDimensionCountDuplicateIndexAndNonFiniteValuesAreRejected() {
        var invalid=List.of(
            new EmbeddingProjection.Batch("other","v1",2,List.of(new EmbeddingProjection.Vector(0,List.of(1d,2d)))),
            new EmbeddingProjection.Batch("model","v2",2,List.of(new EmbeddingProjection.Vector(0,List.of(1d,2d)))),
            new EmbeddingProjection.Batch("model","v1",3,List.of(new EmbeddingProjection.Vector(0,List.of(1d,2d,3d)))),
            batch(List.of()),batch(List.of(new EmbeddingProjection.Vector(0,List.of(1d)))),
            batch(List.of(new EmbeddingProjection.Vector(0,List.of(Double.NaN,2d)))),
            batch(List.of(new EmbeddingProjection.Vector(1,List.of(1d,2d)))));
        for(var response:invalid) assertEquals("EMBEDDING_PROTOCOL_INVALID",assertThrows(ApplicationFault.class,
            ()->new EmbeddingProjection(true,"model","v1",2,texts->response).embed(List.of("fixture"),true)).code());
        var duplicate=batch(List.of(new EmbeddingProjection.Vector(0,List.of(1d,2d)),new EmbeddingProjection.Vector(0,List.of(3d,4d))));
        assertThrows(ApplicationFault.class,()->new EmbeddingProjection(true,"model","v1",2,texts->duplicate).embed(List.of("a","b"),true));
    }
    @Test void inputIndicesRestoreProviderOrderAndProjectionNeverMixesModelVersions() {
        var response=batch(List.of(new EmbeddingProjection.Vector(1,List.of(3d,4d)),new EmbeddingProjection.Vector(0,List.of(1d,2d))));
        var result=new EmbeddingProjection(true,"model","v1",2,texts->response).embed(List.of("a","b"),true);
        assertEquals("VECTOR_READY",result.mode());
        assertEquals(List.of(List.of(1d,2d),List.of(3d,4d)),result.vectors());
        assertThrows(ApplicationFault.class,()->new EmbeddingProjection(true,"model","v1",2,texts->response).embed(List.of("x".repeat(4001)),true));
    }
    @Test void reciprocalRankFusionUsesRanksAndDeduplicatesEachCandidateList() {
        var result=ReciprocalRankFusion.fuse(List.of("a","b","a"),List.of("b","c"),8);
        assertEquals(List.of("b","a","c"),result.stream().map(ReciprocalRankFusion.Hit::id).toList());
        assertEquals(1d/62+1d/61,result.getFirst().score(),0.00000001);
        assertEquals(1d/61,result.get(1).score(),0.00000001);
    }
    private static EmbeddingProjection.Batch batch(List<EmbeddingProjection.Vector> vectors) {
        return new EmbeddingProjection.Batch("model","v1",2,vectors);
    }
}
