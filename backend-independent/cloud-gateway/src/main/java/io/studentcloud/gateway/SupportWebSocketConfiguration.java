package io.studentcloud.gateway;

import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.WebsocketServerSpec;

/** A gateway upgrades the browser before opening its upstream socket; denial must close the upgraded peer explicitly. */
@Configuration(proxyBeanMethods=false)
public class SupportWebSocketConfiguration {
    @Bean @Primary WebSocketService supportGatewayWebSocketService() {
        var strategy=new ReactorNettyRequestUpgradeStrategy(()->WebsocketServerSpec.builder().maxFramePayloadLength(256));
        var delegate=new HandshakeWebSocketService(strategy);
        return (exchange,handler)->delegate.handleRequest(exchange,new WebSocketHandler() {
            @Override public List<String> getSubProtocols() {return handler.getSubProtocols();}
            @Override public Mono<Void> handle(WebSocketSession peer) {
                return handler.handle(peer).onErrorResume(failure->peer.close(authorizationDenied(failure)
                        ? CloseStatus.POLICY_VIOLATION : CloseStatus.SERVER_ERROR));
            }
        });
    }
    private static boolean authorizationDenied(Throwable failure) {
        for(int depth=0;failure!=null && depth<8;depth++,failure=failure.getCause()) {
            if(failure instanceof WebSocketClientHandshakeException rejected && rejected.response()!=null) {
                int code=rejected.response().status().code();
                return code==401 || code==403;
            }
        }
        return false;
    }
}
