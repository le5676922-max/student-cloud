package io.studentcloud.content.config;
import io.studentcloud.drive.application.MountService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public final class RecycleRetention {
    private final MountService mounts;
    public RecycleRetention(MountService mounts){this.mounts=mounts;}
    @Scheduled(initialDelay=900000,fixedDelay=900000) public void clean(){mounts.cleanExpiredRecycle();}
}
