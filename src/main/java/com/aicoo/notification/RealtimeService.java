package com.aicoo.notification;

import com.aicoo.common.ApiException;
import com.aicoo.security.Policy;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class RealtimeService {
    private final Policy policy;private final NotificationService notifications;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<UUID,AtomicInteger> counts=new ConcurrentHashMap<>();
    private final Semaphore total=new Semaphore(500);
    public RealtimeService(Policy policy,NotificationService notifications){this.policy=policy;this.notifications=notifications;}
    @PreDestroy void shutdown(){executor.shutdownNow();}
    public SseEmitter subscribe(Jwt jwt,UUID workspace,String lastId){
        long initial=0;try{if(lastId!=null)initial=Long.parseLong(lastId);if(initial<0)throw new NumberFormatException();}catch(NumberFormatException ex){throw ApiException.invalid("INVALID_LAST_EVENT_ID");}
        var actor=policy.actor(jwt,workspace);var allowed=notifications.children(actor);
        if(allowed.isEmpty())throw ApiException.forbidden("NO_REALTIME_SCOPE");
        var count=counts.computeIfAbsent(actor.membershipId(),key->new AtomicInteger());
        if(count.incrementAndGet()>3){count.decrementAndGet();throw new ApiException(429,"TOO_MANY_STREAMS","실시간 연결이 너무 많아요.");}
        if(!total.tryAcquire()){count.decrementAndGet();throw new ApiException(503,"REALTIME_CAPACITY","잠시 후 다시 연결해 주세요.");}
        var emitter=new SseEmitter(30_000L);var closed=new AtomicBoolean();
        Runnable cleanup=()->{if(closed.compareAndSet(false,true)){count.decrementAndGet();total.release();}};
        emitter.onCompletion(cleanup);emitter.onTimeout(cleanup);emitter.onError(e->cleanup.run());final long after=initial;
        executor.submit(()->{
            long sequence=after;Instant until=Instant.now().plusSeconds(25);int tick=0;
            try {
                emitter.send(SseEmitter.event().comment("connected").reconnectTime(1500));
                while(!closed.get()&&Instant.now().isBefore(until)){
                    if(jwt.getExpiresAt()==null||!jwt.getExpiresAt().isAfter(Instant.now()))break;
                    var current=policy.actor(jwt,workspace);
                    if(!notifications.children(current).equals(allowed))break; // immediately stop a changed subscription
                    for(var message:notifications.replay(current,sequence,allowed)){
                        emitter.send(SseEmitter.event().id(message.get("sequence").toString()).name((String)message.get("type")).data(message));sequence=((Number)message.get("sequence")).longValue();
                    }
                    if(tick++%10==0)emitter.send(SseEmitter.event().comment("heartbeat"));
                    Thread.sleep(1000);
                }
                emitter.complete();
            } catch(Exception ex){emitter.complete();}finally{cleanup.run();}
        });return emitter;
    }
}
