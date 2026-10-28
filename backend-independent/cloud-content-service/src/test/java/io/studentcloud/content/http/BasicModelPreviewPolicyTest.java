package io.studentcloud.content.http;

import io.studentcloud.common.ApplicationFault;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicModelPreviewPolicyTest {
    private static final String OBJ="v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n";
    private static final String STL="solid sample\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid sample\n";

    @Test void acceptsBinaryStlEvenWhenItsHeaderStartsWithSolidAndAsciiStl(){
        byte[] bytes=binary(1);System.arraycopy("solid misleading header".getBytes(StandardCharsets.US_ASCII),0,bytes,0,23);
        assertDoesNotThrow(()->BasicModelPreviewPolicy.validate("stl",bytes));
        assertDoesNotThrow(()->validate("stl",STL));
    }
    @Test void binaryStlRequiresAnExactLengthFiniteValuesAndPointBudget(){
        byte[] trailing=java.util.Arrays.copyOf(binary(1),135);
        assertFault(415,()->BasicModelPreviewPolicy.validate("stl",trailing));
        byte[] malformed=binary(1);ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(80,2);
        assertFault(415,()->BasicModelPreviewPolicy.validate("stl",malformed));
        byte[] nonfinite=binary(1);ByteBuffer.wrap(nonfinite).order(ByteOrder.LITTLE_ENDIAN).putFloat(96,Float.NaN);
        assertFault(415,()->BasicModelPreviewPolicy.validate("stl",nonfinite));
        byte[] normal=binary(1);ByteBuffer.wrap(normal).order(ByteOrder.LITTLE_ENDIAN).putFloat(84,Float.POSITIVE_INFINITY);
        assertFault(415,()->BasicModelPreviewPolicy.validate("stl",normal));
        assertFault(413,()->BasicModelPreviewPolicy.validate("stl",binary(33334)));
    }
    @Test void asciiStlRejectsIncompleteFacetsNonfiniteCoordinatesAndDegenerateTriangles(){
        assertFault(415,()->validate("stl",STL.replace("endfacet\n","")));
        assertFault(415,()->validate("stl",STL.replace("vertex 1 0 0","vertex NaN 0 0")));
        assertFault(415,()->validate("stl",STL.replace("vertex 1 0 0","vertex 1000000001 0 0")));
        assertFault(415,()->validate("stl",STL.replace("vertex 0 1 0","vertex 2 0 0")));
        assertFault(415,()->validate("stl",STL+"solid second\nendsolid second\n"));
        assertFault(415,()->validate("stl",OBJ));
    }
    @Test void objSupportsPositiveAndNegativeLocalIndicesAndConvexCoplanarQuads(){
        assertDoesNotThrow(()->validate("obj",OBJ));
        String object="o Square\ng local-group\ns off\nv 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 0 1\nf -4/-4/-1 -3/-3/-1 -2/-2/-1 -1/-1/-1\n";
        assertDoesNotThrow(()->validate("obj",object));
        assertDoesNotThrow(()->validate("obj",OBJ.replace("f 1 2 3","vn 0 0 1\nf 1//1 2//1 3//1")));
    }
    @Test void objRejectsExternalMaterialTextureAndUnknownInstructions(){
        for(String directive:java.util.List.of("mtllib materials.mtl","usemtl any","map_Kd https://outside.invalid/a.png","call file.obj","csh echo ignored","curv 0 1 2 3"))
            assertFault(415,()->validate("obj",OBJ+directive+"\n"));
    }
    @Test void objIndicesMustReferToDefinedPointsTexturesAndNormals(){
        for(String face:java.util.List.of("f 0 2 3","f 1 2 4","f -4 -2 -1","f 1/1 2/1 3/1","f 1//1 2//1 3//1","f 1/ 2/ 3/","f 1 2","f 1 2 3 1 2"))
            assertFault(415,()->validate("obj",OBJ.substring(0,OBJ.indexOf("f "))+face+"\n"));
        assertFault(415,()->validate("obj","f 1 2 3\n"+OBJ));
    }
    @Test void objRejectsNonFiniteHugeDegenerateConcaveCrossedAndNonplanarFaces(){
        assertFault(415,()->validate("obj",OBJ.replace("v 1 0 0","v Infinity 0 0")));
        assertFault(415,()->validate("obj",OBJ.replace("v 1 0 0","v 1e10 0 0")));
        assertFault(415,()->validate("obj",OBJ.replace("v 0 1 0","v 2 0 0")));
        for(String vertices:java.util.List.of(
                "v 0 0 0\nv 1 0 0\nv 0.25 0.25 0\nv 0 1 0\n",
                "v 0 0 0\nv 1 1 0\nv 0 1 0\nv 1 0 0\n",
                "v 0 0 0\nv 1 0 0\nv 1 1 1\nv 0 1 0\n"))
            assertFault(415,()->validate("obj",vertices+"f 1 2 3 4\n"));
    }
    @Test void objPointNormalTextureAndTriangleCountsAreBounded(){
        for(String prefix:java.util.List.of("v 0 0 0\n","vn 0 0 1\n","vt 0 0\n"))
            assertFault(413,()->validate("obj",prefix.repeat(100001)));
        String vertices=OBJ.substring(0,OBJ.indexOf("f "));
        assertFault(413,()->validate("obj",vertices+"f 1 2 3\n".repeat(100001)));
        assertFault(413,()->BasicModelPreviewPolicy.validate("obj",new byte[10*1024*1024+1]));
    }
    @Test void textModelsRejectBinaryEncodingExcessiveLinesAndEmptyInput(){
        assertFault(415,()->BasicModelPreviewPolicy.validate("obj",new byte[]{(byte)255,(byte)254,0,0}));
        assertFault(415,()->validate("obj","#"+"x".repeat(4096)+"\n"+OBJ));
        assertFault(415,()->BasicModelPreviewPolicy.validate("obj",new byte[0]));
    }

    static byte[] binary(int triangles){
        var data=ByteBuffer.allocate(84+triangles*50).order(ByteOrder.LITTLE_ENDIAN);data.position(80);data.putInt(triangles);
        for(int index=0;index<triangles;index++)data.putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putShort((short)0);
        return data.array();
    }
    private static void validate(String extension,String text){BasicModelPreviewPolicy.validate(extension,text.getBytes(StandardCharsets.UTF_8));}
    private static void assertFault(int status,org.junit.jupiter.api.function.Executable action){assertEquals(status,assertThrows(ApplicationFault.class,action).httpStatus());}
}
