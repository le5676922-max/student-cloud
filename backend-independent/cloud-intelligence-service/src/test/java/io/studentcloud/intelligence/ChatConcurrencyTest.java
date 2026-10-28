package io.studentcloud.intelligence;

import io.studentcloud.account.*;
import io.studentcloud.common.ApplicationFault;
import io.studentcloud.intelligence.ai.ChatService;
import io.studentcloud.intelligence.http.ChatController;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatConcurrencyTest {
    @Test void simultaneousDistinctIdsCannotExceedPerAccountOrGlobalStreamSlots() throws Exception {
        assertSlots(1,2);
        assertSlots(16,8);
    }
    private static void assertSlots(int actors,int expected) throws Exception {
        CountDownLatch hold=new CountDownLatch(1),start=new CountDownLatch(1);
        AccountDirectory accounts=new AccountDirectory(){
            public Actor account(long id){
                try{hold.await(10,TimeUnit.SECONDS);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
                throw new ApplicationFault("CANCELLED",401,"fixture finished");
            }
            public boolean workspaceEnabled(long id){return true;}
        };
        var chat=new ChatService(accounts,null,null,null,null,null,null);
        var controller=new ChatController(chat,null,null);
        record Accepted(String id,Actor actor){}
        var accepted=new CopyOnWriteArrayList<Accepted>();
        try(var pool=Executors.newFixedThreadPool(32)){
            var futures=new ArrayList<Future<?>>();
            for(int index=0;index<32;index++){
                long id=index%actors+1;
                Actor actor=new Actor(id,"account"+id,"name",null,Role.USER,7L);
                futures.add(pool.submit(()->{
                    start.await();String requestId=UUID.randomUUID().toString();
                    try{
                        controller.stream(requestId,new ChatService.Request("hello",List.of(),"/","concise",null,false,null,null,null),request(actor));
                        accepted.add(new Accepted(requestId,actor));
                    }catch(ApplicationFault rejected){assertEquals(429,rejected.httpStatus());}
                    return null;
                }));
            }
            start.countDown();for(var future:futures)future.get(8,TimeUnit.SECONDS);
            assertEquals(expected,accepted.size());
        }finally{
            for(var item:accepted)controller.cancel(item.id(),request(item.actor()));
            hold.countDown();
        }
    }
    private static HttpServletRequest request(Actor actor){
        return (HttpServletRequest)Proxy.newProxyInstance(ChatConcurrencyTest.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},
            (proxy,method,args)->method.getName().equals("getAttribute")&&Actor.class.getName().equals(args[0])?actor:null);
    }
}
