package ljl.bilibili.gateway.service.userinfo.model;

import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.util.Result;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.concurrent.CompletableFuture;

@Data
@Accessors(chain = true)
public class UserInfoAggregateTask {
    private String requestId;
    private Integer selfId;
    private Integer visitedId;
    private long enqueueTimeMillis;
    private CompletableFuture<Result<UserInfoBatchResponseItem>> future;
    private volatile boolean cancelled;
}
