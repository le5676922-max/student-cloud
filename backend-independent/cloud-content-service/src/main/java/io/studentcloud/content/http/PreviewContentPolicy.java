package io.studentcloud.content.http;

import io.studentcloud.common.ApplicationFault;
import io.studentcloud.drive.FileRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

/** Bounded representation policy. Inspects containers only; never runs a document/image/media decoder. */
final class PreviewContentPolicy {
    static final long MAX_BINARY_BYTES=20L*1024*1024,MAX_TEXT_BYTES=1024L*1024;
    static final long MAX_OFFICE_BYTES=10L*1024*1024,MAX_MEDIA_BYTES=50L*1024*1024;
    private static final long MAX_IMAGE_PIXELS=20_000_000;
    private static final int MAX_ZIP_ENTRIES=1000,MAX_ZIP_ENTRY=4*1024*1024,MAX_ZIP_EXPANSION=20*1024*1024;
    private static final Map<String,String> IMAGES=Map.of("png","image/png","jpg","image/jpeg","jpeg","image/jpeg","gif","image/gif","webp","image/webp","bmp","image/bmp");
    private static final Set<String> TEXT=Set.of("txt","log","csv","tsv","json","jsonc","xml","yaml","yml","ini","conf","config","properties","toml","env","gitignore","java","kt","kts","scala","groovy","py","pyw","rb","php","go","rs","c","h","cc","cpp","cxx","hpp","cs","sql","sh","bash","zsh","bat","cmd","ps1","r","lua","swift","dart","js","mjs","cjs","jsx","ts","tsx","vue","svelte","html","htm","css","scss","sass","less","svg","dockerfile","makefile");
    private static final Map<String,String> AUDIO=Map.of("mp3","audio/mpeg","m4a","audio/mp4","aac","audio/aac","wav","audio/wav","ogg","audio/ogg","oga","audio/ogg","opus","audio/ogg","flac","audio/flac");
    private static final Map<String,String> VIDEO=Map.of("mp4","video/mp4","m4v","video/mp4","webm","video/webm","ogv","video/ogg");
    private static final Map<String,String> OFFICE=Map.of("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document","xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","pptx","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    record Format(String kind,String mediaType,long limit,String extension){
        Format(String kind,String mediaType,long limit){this(kind,mediaType,limit,"");}
    }

    private PreviewContentPolicy(){}

    static Format format(FileRecord file){
        if(file.directory())return null;
        String name=file.name().toLowerCase(Locale.ROOT),extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1):name;
        if(IMAGES.containsKey(extension))return new Format("image",IMAGES.get(extension),MAX_BINARY_BYTES);
        if("pdf".equals(extension))return new Format("pdf","application/pdf",MAX_BINARY_BYTES);
        if(Set.of("md","markdown").contains(extension))return new Format("markdown","text/plain;charset=UTF-8",MAX_TEXT_BYTES);
        if(TEXT.contains(extension)||Set.of("readme","license","licence","notice").contains(name))return new Format("text","text/plain;charset=UTF-8",MAX_TEXT_BYTES);
        if(AUDIO.containsKey(extension))return new Format("audio",AUDIO.get(extension),MAX_MEDIA_BYTES);
        if(VIDEO.containsKey(extension))return new Format("video",VIDEO.get(extension),MAX_MEDIA_BYTES);
        if(OFFICE.containsKey(extension))return new Format("office",OFFICE.get(extension),MAX_OFFICE_BYTES);
        if(Set.of("stl","obj").contains(extension))return new Format("model","application/octet-stream",MAX_OFFICE_BYTES,extension);
        return null;
    }

    static void validate(Format type,byte[] bytes)throws IOException{
        if(Set.of("text","markdown").contains(type.kind())){validateText(bytes);return;}
        if("office".equals(type.kind())){validateOffice(type,bytes);return;}
        if("model".equals(type.kind())){BasicModelPreviewPolicy.validate(type.extension(),bytes);return;}
        if("pdf".equals(type.kind())){if(!ascii(bytes,0,"%PDF-")||bytes.length<8)throw unsupported();return;}
        if(Set.of("audio","video").contains(type.kind())){validateMedia(type.mediaType(),bytes);return;}
        validateImage(type.mediaType(),bytes);
    }

    private static void validateText(byte[] bytes){
        try{
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf('\0')>=0)throw unsupported();
        }catch(CharacterCodingException invalid){throw fault("PREVIEW_ENCODING_UNSUPPORTED",415,"文本不是有效的UTF-8格式，请下载后使用文本编辑器查看");}
    }

    private static void validateMedia(String media,byte[] bytes){
        boolean valid=switch(media){
            case "audio/mpeg"->mp3(bytes);
            case "audio/aac"->bytes.length>=7&&(bytes[0]&255)==255&&((bytes[1]&255)&246)==240&&((bytes[2]&60)>>2)!=15;
            case "audio/mp4","video/mp4"->mp4(bytes);
            case "audio/wav"->bytes.length>=44&&ascii(bytes,0,"RIFF")&&ascii(bytes,8,"WAVE");
            case "audio/flac"->bytes.length>=42&&ascii(bytes,0,"fLaC")&&(bytes[4]&127)==0&&bytes[5]==0&&bytes[6]==0&&(bytes[7]&255)==34;
            case "audio/ogg","video/ogg"->bytes.length>=27&&ascii(bytes,0,"OggS")&&bytes[4]==0&&27+(bytes[26]&255)<=bytes.length;
            case "video/webm"->webm(bytes);
            default->false;
        };
        if(!valid)throw unsupported();
    }
    private static boolean mp3(byte[] bytes){
        int offset=0;
        if(ascii(bytes,0,"ID3")){
            if(bytes.length<10||(bytes[3]&255)<2||(bytes[3]&255)>4)return false;
            for(int index=6;index<10;index++)if((bytes[index]&128)!=0)return false;
            offset=10+((bytes[6]&127)<<21)+((bytes[7]&127)<<14)+((bytes[8]&127)<<7)+(bytes[9]&127);
            if((bytes[5]&16)!=0)offset+=10;
        }
        if(offset>bytes.length-4)return false;
        int first=bytes[offset]&255,second=bytes[offset+1]&255,third=bytes[offset+2]&255;
        return first==255&&(second&224)==224&&((second>>3)&3)!=1&&((second>>1)&3)!=0&&(third>>4)!=0&&(third>>4)!=15&&((third>>2)&3)!=3;
    }
    private static boolean mp4(byte[] bytes){
        if(bytes.length<16||!ascii(bytes,4,"ftyp"))return false;
        long length=be32(bytes,0);
        if(length<16||length>bytes.length||length>4096)return false;
        Set<String> brands=Set.of("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","M4A ","M4B ","M4V ","avc1","dash");
        if(brands.contains(new String(bytes,8,4,StandardCharsets.US_ASCII)))return true;
        for(int at=16;at<=length-4;at+=4)if(brands.contains(new String(bytes,at,4,StandardCharsets.US_ASCII)))return true;
        return false;
    }
    private static boolean webm(byte[] bytes){
        if(bytes.length<12||!starts(bytes,new byte[]{26,69,(byte)223,(byte)163}))return false;
        for(int at=4;at<Math.min(bytes.length-6,4096);at++)if((bytes[at]&255)==66&&(bytes[at+1]&255)==130&&(bytes[at+2]&255)==132&&ascii(bytes,at+3,"webm"))return true;
        return false;
    }

    private static void validateOffice(Format type,byte[] bytes)throws IOException{
        if(!starts(bytes,new byte[]{80,75,3,4}))throw unsupported();
        String main=type.mediaType().contains("wordprocessingml")?"word/document.xml":type.mediaType().contains("spreadsheetml")?"xl/workbook.xml":"ppt/presentation.xml";
        String mainContentType=type.mediaType()+".main+xml";
        byte[] types=null,document=null;int entries=0;long total=0;
        var names=new HashSet<String>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            byte[] buffer=new byte[8192];
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                String name=entry.getName();
                if(++entries>MAX_ZIP_ENTRIES||name.length()>1024||!names.add(name)||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.chars().anyMatch(Character::isISOControl))throw unsupported();
                for(String part:name.split("/"))if(part.equals(".")||part.equals(".."))throw unsupported();
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith("vbaproject.bin")||lower.endsWith("vbadata.xml")||lower.contains("/activex/"))throw unsupported();
                boolean capture=name.equals("[Content_Types].xml")||name.equals(main);
                var output=capture?new ByteArrayOutputStream():null;
                long length=0;
                for(int read;(read=zip.read(buffer))!=-1;){
                    length+=read;total+=read;
                    if(length>MAX_ZIP_ENTRY||total>MAX_ZIP_EXPANSION)throw fault("PREVIEW_DOCUMENT_BUDGET",413,"文档解压后超过预览预算，请下载后查看");
                    if(capture)output.write(buffer,0,read);
                }
                if(name.equals("[Content_Types].xml"))types=output.toByteArray();
                if(name.equals(main))document=output.toByteArray();
            }
        }catch(java.util.zip.ZipException|IllegalArgumentException malformed){throw unsupported();}
        if(types==null||document==null||!completeZip(bytes,names))throw unsupported();
        validateOfficeXml(types,"Types","http://schemas.openxmlformats.org/package/2006/content-types",main,mainContentType);
        String namespace=main.startsWith("word/")?"http://schemas.openxmlformats.org/wordprocessingml/2006/main":main.startsWith("xl/")?"http://schemas.openxmlformats.org/spreadsheetml/2006/main":"http://schemas.openxmlformats.org/presentationml/2006/main";
        validateOfficeXml(document,main.startsWith("word/")?"document":main.startsWith("xl/")?"workbook":"presentation",namespace,null,null);
    }
    private static boolean completeZip(byte[] bytes,Set<String> names){
        for(int at=bytes.length-22;at>=Math.max(0,bytes.length-65557);at--){
            if(!startsAt(bytes,at,new byte[]{80,75,5,6}))continue;
            if(at+22+le16(bytes,at+20)!=bytes.length)continue;
            if(le16(bytes,at+4)!=0||le16(bytes,at+6)!=0||le16(bytes,at+8)!=names.size()||le16(bytes,at+10)!=names.size()||le32(bytes,at+12)+le32(bytes,at+16)!=at)return false;
            long offset=le32(bytes,at+16);
            if(offset>at)return false;
            int cursor=(int)offset;
            var centralNames=new HashSet<String>();
            for(int index=0;index<names.size();index++){
                if(cursor>at-46||!startsAt(bytes,cursor,new byte[]{80,75,1,2}))return false;
                int length=le16(bytes,cursor+28),extra=le16(bytes,cursor+30),comment=le16(bytes,cursor+32);
                long next=(long)cursor+46+length+extra+comment;
                if(next>at||length>1024||le16(bytes,cursor+34)!=0||le32(bytes,cursor+20)>MAX_OFFICE_BYTES||le32(bytes,cursor+24)>MAX_ZIP_ENTRY)return false;
                String name=new String(bytes,cursor+46,length,StandardCharsets.UTF_8);
                if(!names.contains(name)||!centralNames.add(name))return false;
                long localOffset=le32(bytes,cursor+42);
                if(localOffset>offset-4||!startsAt(bytes,(int)localOffset,new byte[]{80,75,3,4}))return false;
                cursor=(int)next;
            }
            return cursor==at&&centralNames.equals(names);
        }
        return false;
    }
    private static void validateOfficeXml(byte[] xml,String root,String namespace,String main,String contentType){
        try{
            var factory=XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
            factory.setXMLResolver((publicId,systemId,baseUri,namespaceUri)->{throw new XMLStreamException("External resources are disabled");});
            var parser=factory.createXMLStreamReader(new ByteArrayInputStream(xml));
            try{
                int depth=0;boolean seenRoot=false,foundType=main==null;
                while(parser.hasNext()){
                    int event=parser.next();
                    if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw unsupported();
                    if(event==XMLStreamConstants.START_ELEMENT){
                        if(++depth>64||parser.getAttributeCount()>100)throw unsupported();
                        if(!seenRoot){seenRoot=true;if(!root.equals(parser.getLocalName())||!namespace.equals(parser.getNamespaceURI()))throw unsupported();}
                        if(main!=null&&"Override".equals(parser.getLocalName())&&("/"+main).equals(parser.getAttributeValue(null,"PartName"))&&contentType.equals(parser.getAttributeValue(null,"ContentType")))foundType=true;
                    }else if(event==XMLStreamConstants.END_ELEMENT)depth--;
                }
                if(!seenRoot||!foundType)throw unsupported();
            }finally{parser.close();}
        }catch(XMLStreamException invalid){throw unsupported();}
    }

    private static void validateImage(String media,byte[] bytes)throws IOException{
        boolean valid=switch(media){
            case "image/png"->starts(bytes,new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg"->starts(bytes,new byte[]{(byte)255,(byte)216,(byte)255});
            case "image/gif"->ascii(bytes,0,"GIF87a")||ascii(bytes,0,"GIF89a");
            case "image/webp"->bytes.length>=30&&ascii(bytes,0,"RIFF")&&ascii(bytes,8,"WEBP");
            case "image/bmp"->bytes.length>=26&&ascii(bytes,0,"BM");
            default->false;
        };
        if(!valid)throw unsupported();
        if("image/webp".equals(media)){validateWebp(bytes);return;}
        try(var image=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(image);if(!readers.hasNext())throw unsupported();
            var reader=readers.next();
            try{reader.setInput(image,true,true);dimensions(reader.getWidth(0),reader.getHeight(0));}finally{reader.dispose();}
        }
    }
    private static void validateWebp(byte[] bytes){
        int width,height;
        if(ascii(bytes,12,"VP8X")){width=1+le24(bytes,24);height=1+le24(bytes,27);}
        else if(ascii(bytes,12,"VP8L")&&(bytes[20]&255)==47){width=1+((bytes[21]&255)|((bytes[22]&63)<<8));height=1+(((bytes[22]&255)>>6)|((bytes[23]&255)<<2)|((bytes[24]&15)<<10));}
        else if(ascii(bytes,12,"VP8 ")&&(bytes[23]&255)==157&&bytes[24]==1&&bytes[25]==42){width=((bytes[26]&255)|((bytes[27]&255)<<8))&16383;height=((bytes[28]&255)|((bytes[29]&255)<<8))&16383;}
        else throw unsupported();
        dimensions(width,height);
    }
    private static void dimensions(int width,int height){if(width<1||height<1||(long)width*height>MAX_IMAGE_PIXELS)throw fault("PREVIEW_IMAGE_BUDGET",413,"图片像素超过预览预算，请下载后查看");}
    private static int le16(byte[] data,int at){return(data[at]&255)|((data[at+1]&255)<<8);}
    private static int le24(byte[] data,int at){return le16(data,at)|((data[at+2]&255)<<16);}
    private static long le32(byte[] data,int at){return Integer.toUnsignedLong(ByteBuffer.wrap(data,at,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());}
    private static long be32(byte[] data,int at){return Integer.toUnsignedLong(ByteBuffer.wrap(data,at,4).getInt());}
    private static boolean ascii(byte[] bytes,int offset,String text){return startsAt(bytes,offset,text.getBytes(StandardCharsets.US_ASCII));}
    private static boolean starts(byte[] bytes,byte[] prefix){return startsAt(bytes,0,prefix);}
    private static boolean startsAt(byte[] bytes,int offset,byte[] prefix){if(offset<0||bytes.length-offset<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(bytes[offset+i]!=prefix[i])return false;return true;}
    private static ApplicationFault unsupported(){return fault("PREVIEW_CONTENT_UNSUPPORTED",415,"文件内容与预览格式不符或已损坏，请下载后查看");}
    private static ApplicationFault fault(String code,int status,String message){return new ApplicationFault(code,status,message);}
}
