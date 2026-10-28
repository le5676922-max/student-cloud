package io.studentcloud.content.http;

import io.studentcloud.common.ApplicationFault;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Geometry only. No material libraries, textures, scripts, filesystem paths or linked resources. */
final class BasicModelPreviewPolicy {
    private static final int MAX_VERTICES=100_000,MAX_TRIANGLES=100_000,MAX_LINE=4096;
    private static final double MAX_VALUE=1_000_000_000d;
    private static final Pattern NUMBER=Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private BasicModelPreviewPolicy(){}

    static void validate(String extension,byte[] bytes){
        if(bytes==null||bytes.length==0)throw invalid();
        if(bytes.length>10*1024*1024)throw budget();
        if("stl".equals(extension)){
            if(bytes.length>=84){
                long count=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,80,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                if(84+50L*count==bytes.length){binaryStl(bytes,count);return;}
            }
            asciiStl(text(bytes));return;
        }
        if("obj".equals(extension)){obj(text(bytes));return;}
        throw invalid();
    }
    private static String text(byte[] bytes){
        try{
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(value.indexOf('\0')>=0)throw invalid();
            return value.startsWith("\ufeff")?value.substring(1):value;
        }catch(CharacterCodingException invalid){throw invalid();}
    }
    private static void binaryStl(byte[] bytes,long count){
        if(count<1||count>MAX_TRIANGLES||count*3>MAX_VERTICES)throw budget();
        var input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);input.position(84);
        var bounds=new Bounds();var triangle=new double[9];
        for(long index=0;index<count;index++){
            for(int n=0;n<3;n++)checked(input.getFloat());
            for(int n=0;n<9;n++)triangle[n]=checked(input.getFloat());
            input.getShort();triangle(triangle,0,3,6);bounds.take(triangle,0);bounds.take(triangle,3);bounds.take(triangle,6);
        }
        bounds.finish();
    }
    private static void asciiStl(String text){
        var lines=new Lines(text);String line=lines.next();
        if(line==null||!keywordLine(line,"solid"))throw invalid();
        int triangles=0;var bounds=new Bounds();
        for(;;){
            line=lines.next();if(line==null)throw invalid();
            if(keywordLine(line,"endsolid")){
                if(triangles==0||lines.next()!=null)throw invalid();bounds.finish();return;
            }
            String[] normal=parts(line);
            if(normal.length!=5||!word(normal[0],"facet")||!word(normal[1],"normal"))throw invalid();
            for(int n=2;n<5;n++)number(normal[n]);
            if(++triangles>MAX_TRIANGLES||triangles*3>MAX_VERTICES)throw budget();
            String[] outer=parts(lines.required());
            if(outer.length!=2||!word(outer[0],"outer")||!word(outer[1],"loop"))throw invalid();
            double[] values=new double[9];
            for(int point=0;point<3;point++){
                String[] vertex=parts(lines.required());
                if(vertex.length!=4||!word(vertex[0],"vertex"))throw invalid();
                for(int dimension=0;dimension<3;dimension++)values[3*point+dimension]=number(vertex[dimension+1]);
                bounds.take(values,3*point);
            }
            if(!word(lines.required(),"endloop")||!word(lines.required(),"endfacet"))throw invalid();
            triangle(values,0,3,6);
        }
    }
    private static boolean keywordLine(String line,String keyword){return line.equalsIgnoreCase(keyword)||(line.length()>keyword.length()&&line.regionMatches(true,0,keyword,0,keyword.length())&&Character.isWhitespace(line.charAt(keyword.length())));}

    private static void obj(String text){
        var lines=new Lines(text);int vertices=0,normals=0,textureCoordinates=0,triangles=0;
        double[] positions=new double[MAX_VERTICES*3];var bounds=new Bounds();
        for(String line=lines.next();line!=null;line=lines.next()){
            int comment=line.indexOf('#');if(comment>=0)line=line.substring(0,comment).trim();
            if(line.isEmpty())continue;
            String[] tokens=parts(line);
            switch(tokens[0]){
                case "v"->{
                    if(tokens.length!=4)throw invalid();
                    if(vertices>=MAX_VERTICES)throw budget();
                    for(int at=0;at<3;at++)positions[3*vertices+at]=number(tokens[at+1]);
                    bounds.take(positions,3*vertices);vertices++;
                }
                case "vn"->{if(tokens.length!=4)throw invalid();if(++normals>MAX_VERTICES)throw budget();for(int at=1;at<4;at++)number(tokens[at]);}
                case "vt"->{if(tokens.length<2||tokens.length>4)throw invalid();if(++textureCoordinates>MAX_VERTICES)throw budget();for(int at=1;at<tokens.length;at++)number(tokens[at]);}
                case "f"->{
                    if(tokens.length!=4&&tokens.length!=5)throw invalid();
                    int[] face=new int[tokens.length-1];
                    for(int at=1;at<tokens.length;at++)face[at-1]=faceIndex(tokens[at],vertices,textureCoordinates,normals)*3;
                    if(face.length==4)quad(positions,face);
                    triangle(positions,face[0],face[1],face[2]);
                    if(face.length==4)triangle(positions,face[0],face[2],face[3]);
                    triangles+=face.length-2;if(triangles>MAX_TRIANGLES)throw budget();
                }
                case "o","g","s"->{/* Only local labels/smoothing hints; never resolved as resources. */}
                default->throw invalid();
            }
        }
        if(vertices<3||triangles==0)throw invalid();bounds.finish();
    }
    private static int faceIndex(String token,int vertices,int textures,int normals){
        String[] indices=token.split("/",-1);
        if(indices.length<1||indices.length>3)throw invalid();
        int point=index(indices[0],vertices);
        if(indices.length==2){index(indices[1],textures);}
        else if(indices.length==3){if(!indices[1].isEmpty())index(indices[1],textures);index(indices[2],normals);}
        return point;
    }
    private static int index(String token,int count){
        if(token.length()>11||!token.matches("[+-]?[0-9]+"))throw invalid();
        try{int raw=Integer.parseInt(token),value=raw>0?raw-1:count+raw;if(raw==0||value<0||value>=count)throw invalid();return value;}
        catch(NumberFormatException malformed){throw invalid();}
    }
    private static void triangle(double[] values,int a,int b,int c){
        double x1=values[b]-values[a],y1=values[b+1]-values[a+1],z1=values[b+2]-values[a+2];
        double x2=values[c]-values[a],y2=values[c+1]-values[a+1],z2=values[c+2]-values[a+2];
        double x=y1*z2-z1*y2,y=z1*x2-x1*z2,z=x1*y2-y1*x2;
        double area=x*x+y*y+z*z;if(!Double.isFinite(area)||area<=0)throw invalid();
    }
    private static void quad(double[] values,int[] points){
        double[] normal=new double[3];
        int a=points[0],b=points[1],c=points[2],d=points[3];
        normal[0]=(values[b+1]-values[a+1])*(values[c+2]-values[a+2])-(values[b+2]-values[a+2])*(values[c+1]-values[a+1]);
        normal[1]=(values[b+2]-values[a+2])*(values[c]-values[a])-(values[b]-values[a])*(values[c+2]-values[a+2]);
        normal[2]=(values[b]-values[a])*(values[c+1]-values[a+1])-(values[b+1]-values[a+1])*(values[c]-values[a]);
        double normalLength=Math.sqrt(normal[0]*normal[0]+normal[1]*normal[1]+normal[2]*normal[2]);
        if(!Double.isFinite(normalLength)||normalLength<=0)throw invalid();
        double scale=0;
        for(int dimension=0;dimension<3;dimension++){
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int point:points){min=Math.min(min,values[point+dimension]);max=Math.max(max,values[point+dimension]);}
            scale=Math.max(scale,max-min);
        }
        double distance=Math.abs((values[d]-values[a])*normal[0]+(values[d+1]-values[a+1])*normal[1]+(values[d+2]-values[a+2])*normal[2])/normalLength;
        if(!Double.isFinite(distance)||distance>scale*1e-6)throw invalid();
        double tolerance=scale*scale*normalLength*1e-12;
        for(int point=0;point<4;point++){
            int p=points[point],q=points[(point+1)%4],r=points[(point+2)%4];
            double x1=values[q]-values[p],y1=values[q+1]-values[p+1],z1=values[q+2]-values[p+2];
            double x2=values[r]-values[q],y2=values[r+1]-values[q+1],z2=values[r+2]-values[q+2];
            double turn=(y1*z2-z1*y2)*normal[0]+(z1*x2-x1*z2)*normal[1]+(x1*y2-y1*x2)*normal[2];
            if(!Double.isFinite(turn)||turn<=tolerance)throw invalid();
        }
    }
    private static double number(String token){
        if(token.length()>64||!NUMBER.matcher(token).matches())throw invalid();
        try{return checked(Double.parseDouble(token));}catch(NumberFormatException invalid){throw invalid();}
    }
    private static double checked(double value){if(!Double.isFinite(value)||Math.abs(value)>MAX_VALUE)throw invalid();return value;}
    private static String[] parts(String line){return line.split("\\s+");}
    private static boolean word(String text,String expected){return text.toLowerCase(Locale.ROOT).equals(expected);}
    private static final class Lines{
        private final String text;private int at;
        Lines(String text){this.text=text;}
        String next(){
            while(at<text.length()){
                int end=text.indexOf('\n',at);if(end<0)end=text.length();
                if(end-at>MAX_LINE)throw invalid();String line=text.substring(at,end).trim();at=end+1;
                if(!line.isEmpty())return line;
            }
            return null;
        }
        String required(){String line=next();if(line==null)throw invalid();return line;}
    }
    private static final class Bounds{
        private final double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        void take(double[] values,int start){for(int dimension=0;dimension<3;dimension++){min[dimension]=Math.min(min[dimension],values[start+dimension]);max[dimension]=Math.max(max[dimension],values[start+dimension]);}}
        void finish(){double span=Math.max(max[0]-min[0],Math.max(max[1]-min[1],max[2]-min[2]));if(!Double.isFinite(span)||span<=0)throw invalid();}
    }
    private static ApplicationFault invalid(){return new ApplicationFault("PREVIEW_MODEL_INVALID",415,"模型内容不支持安全预览；请使用不含外部资源的 STL 或 OBJ 几何文件");}
    private static ApplicationFault budget(){return new ApplicationFault("PREVIEW_MODEL_BUDGET",413,"模型超过点数或三角形预览预算，请下载后查看");}
}
