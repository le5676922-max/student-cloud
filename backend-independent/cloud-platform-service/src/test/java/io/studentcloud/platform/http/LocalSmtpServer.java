package io.studentcloud.platform.http;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** A one-message loopback SMTP receiver; no mail can leave this test process. */
final class LocalSmtpServer implements AutoCloseable {
    private final ServerSocket listener=new ServerSocket();
    private final java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Future<CapturedMessage> message;

    LocalSmtpServer()throws Exception{
        listener.bind(new InetSocketAddress("127.0.0.1",0));
        listener.setSoTimeout(5_000);
        message=executor.submit(this::receive);
    }
    int port(){return listener.getLocalPort();}
    CapturedMessage message()throws Exception{return message.get(5,TimeUnit.SECONDS);}

    private CapturedMessage receive()throws Exception{
        try(var socket=listener.accept();
            var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            var output=new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII)){
            socket.setSoTimeout(5_000);
            reply(output,"220 localhost test SMTP");
            String recipient=null;
            MimeMessage received=null;
            for(String line;(line=input.readLine())!=null;){
                String command=line.toUpperCase(Locale.ROOT);
                if(command.startsWith("EHLO ") || command.startsWith("HELO ") || command.startsWith("MAIL FROM:"))reply(output,"250 ok");
                else if(command.startsWith("RCPT TO:")){recipient=line;reply(output,"250 ok");}
                else if(command.equals("DATA")){
                    reply(output,"354 end with a dot");
                    var data=new StringBuilder();
                    while((line=input.readLine())!=null && !line.equals(".")){
                        if(line.startsWith(".."))line=line.substring(1);
                        data.append(line).append("\r\n");
                    }
                    received=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.US_ASCII)));
                    reply(output,"250 queued");
                }else if(command.equals("QUIT")){
                    reply(output,"221 bye");
                    if(recipient==null || received==null)throw new IllegalStateException("SMTP closed before message delivery");
                    return new CapturedMessage(recipient,received);
                }else throw new IllegalStateException("Unexpected test SMTP command");
            }
            throw new IllegalStateException("SMTP closed without QUIT");
        }
    }
    private static void reply(OutputStreamWriter output,String line)throws Exception{output.write(line+"\r\n");output.flush();}
    @Override public void close()throws Exception{listener.close();executor.shutdownNow();}
    record CapturedMessage(String recipient,MimeMessage message){ }
}
