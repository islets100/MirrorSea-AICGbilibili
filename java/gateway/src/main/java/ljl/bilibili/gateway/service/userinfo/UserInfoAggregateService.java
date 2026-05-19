package ljl.bilibili.gateway.service.userinfo;

import com.alibaba.fastjson.JSON;
import ljl.bilibili.client.pojo.UserInfoBatchRequest;
import ljl.bilibili.client.pojo.UserInfoBatchRequestItem;
import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.client.user_center.UserInfoClient;
import ljl.bilibili.gateway.service.userinfo.model.UserInfoAggregateTask;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class UserInfoAggregateService implements InitializingBean, DisposableBean {

    private static final String SECOND_QPS_KEY_PREFIX = "gateway:userInfo:qps:sec:";
    private static final String MINUTE_QPS_KEY_PREFIX = "gateway:userInfo:qps:min:";
    private static final String FALLBACK_BATCH_KEY = "gateway:userInfo:fallback:batch";

    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private UserInfoClient userInfoClient;
    @Resource
    private DefaultRedisScript<Long> incrementWindowScript;

    @Value("${user-info-aggregate.enabled:true}")
    private Boolean enabled;
    @Value("${user-info-aggregate.hot-threshold-per-second:100}")
    private Integer hotThresholdPerSecond;
    @Value("${user-info-aggregate.queue-capacity:2000}")
    private Integer queueCapacity;
    @Value("${user-info-aggregate.max-batch-size:100}")
    private Integer maxBatchSize;
    @Value("${user-info-aggregate.max-wait-millis:10}")
    private Long maxWaitMillis;
    @Value("${user-info-aggregate.future-timeout-millis:120}")
    private Long futureTimeoutMillis;
    @Value("${user-info-aggregate.second-window-expire-seconds:2}")
    private Long secondWindowExpireSeconds;
    @Value("${user-info-aggregate.minute-window-expire-seconds:120}")
    private Long minuteWindowExpireSeconds;

    private final AtomicLong aggregateHitCount = new AtomicLong();
    private final AtomicLong aggregateBypassCount = new AtomicLong();
    private final AtomicLong queueOfferSuccessCount = new AtomicLong();
    private final AtomicLong queueOfferRejectCount = new AtomicLong();
    private final AtomicLong aggregateTimeoutFallbackCount = new AtomicLong();
    private final AtomicLong aggregateMissingFallbackCount = new AtomicLong();
    private final AtomicLong aggregateBatchSuccessCount = new AtomicLong();
    private final AtomicLong aggregateBatchFailureCount = new AtomicLong();
    private final AtomicLong aggregateCompletedCount = new AtomicLong();
    private final AtomicLong aggregateCompleteSkippedCount = new AtomicLong();
    private final AtomicLong aggregateFutureCompletedByFallbackCount = new AtomicLong();

    private volatile boolean running;
    private Thread workerThread;
    private LinkedBlockingQueue<UserInfoAggregateTask> taskQueue;

    @Override
    public void afterPropertiesSet() {
        int capacity = queueCapacity == null || queueCapacity <= 0 ? 2000 : queueCapacity;
        this.taskQueue = new LinkedBlockingQueue<>(capacity);
        this.running = true;
        this.workerThread = new Thread(this::consumeLoop, "user-info-aggregate-worker");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    @Override
    public void destroy() throws InterruptedException {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread.join(1000L);
        }
    }

    public boolean shouldAggregate(Integer visitedId) {
        if (!Boolean.TRUE.equals(enabled) || visitedId == null) {
            aggregateBypassCount.incrementAndGet();
            return false;
        }
        long currentSecond = System.currentTimeMillis() / 1000;
        String secondKey = SECOND_QPS_KEY_PREFIX + visitedId + ":" + currentSecond;
        Long secondCount = incrementWindow(secondKey, secondWindowExpireSeconds);

        String minuteKey = MINUTE_QPS_KEY_PREFIX + visitedId + ":" + buildMinuteSlot();
        incrementWindow(minuteKey, minuteWindowExpireSeconds);
        boolean hot = secondCount != null && secondCount >= hotThresholdPerSecond;
        if (hot) {
            aggregateHitCount.incrementAndGet();
        } else {
            aggregateBypassCount.incrementAndGet();
        }
        return hot;
    }

    public Result<UserInfoBatchResponseItem> getAggregatedOrFallback(Integer selfId, Integer visitedId) {
        UserInfoAggregateTask task = new UserInfoAggregateTask()
                .setRequestId(UUID.randomUUID().toString())
                .setSelfId(selfId)
                .setVisitedId(visitedId)
                .setEnqueueTimeMillis(System.currentTimeMillis())
                .setFuture(new CompletableFuture<>());
        boolean offered = taskQueue.offer(task);
        if (!offered) {
            queueOfferRejectCount.incrementAndGet();
            log.warn("user info aggregate queue is full, fallback to single request, visitedId={}", visitedId);
            return fallbackSingle(selfId, visitedId);
        }
        queueOfferSuccessCount.incrementAndGet();
        try {
            return task.getFuture().get(futureTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            aggregateTimeoutFallbackCount.incrementAndGet();
            task.setCancelled(true);
            Result<UserInfoBatchResponseItem> fallbackResult = fallbackSingle(selfId, visitedId);
            completeTaskFuture(task, fallbackResult, "timeout-fallback");
            return fallbackResult;
        }
    }

    private void consumeLoop() {
        while (running) {
            try {
                UserInfoAggregateTask firstTask = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                if (firstTask == null) {
                    continue;
                }
                List<UserInfoAggregateTask> batch = new ArrayList<>();
                batch.add(firstTask);
                long deadline = System.currentTimeMillis() + maxWaitMillis;
                while (batch.size() < maxBatchSize) {
                    long waitMillis = deadline - System.currentTimeMillis();
                    if (waitMillis <= 0) {
                        break;
                    }
                    UserInfoAggregateTask nextTask = taskQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
                    if (nextTask == null) {
                        break;
                    }
                    batch.add(nextTask);
                }
                flushBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("consume user info aggregate batch failed", e);
            }
        }
    }

    private void flushBatch(List<UserInfoAggregateTask> batch) {
        List<UserInfoAggregateTask> activeTasks = new ArrayList<>();
        for (UserInfoAggregateTask task : batch) {
            if (task != null && !task.isCancelled()) {
                activeTasks.add(task);
            }
        }
        if (activeTasks.isEmpty()) {
            return;
        }

        long startTime = System.currentTimeMillis();
        try {
            UserInfoBatchRequest request = new UserInfoBatchRequest().setItems(new ArrayList<>());
            for (UserInfoAggregateTask task : activeTasks) {
                request.getItems().add(new UserInfoBatchRequestItem()
                        .setRequestId(task.getRequestId())
                        .setSelfId(task.getSelfId())
                        .setVisitedId(task.getVisitedId()));
            }
            Result<List<UserInfoBatchResponseItem>> result = userInfoClient.getUserInfoBatch(request);
            List<UserInfoBatchResponseItem> responseItems = result == null || result.getData() == null ? Collections.emptyList() : result.getData();
            Map<String, UserInfoBatchResponseItem> responseMap = new LinkedHashMap<>();
            for (UserInfoBatchResponseItem responseItem : responseItems) {
                responseMap.put(responseItem.getRequestId(), responseItem);
            }
            int fallbackCount = 0;
            for (UserInfoAggregateTask task : activeTasks) {
                UserInfoBatchResponseItem responseItem = responseMap.get(task.getRequestId());
                Result<UserInfoBatchResponseItem> taskResult;
                if (responseItem != null) {
                    taskResult = Result.data(responseItem);
                } else {
                    fallbackCount++;
                    aggregateMissingFallbackCount.incrementAndGet();
                    taskResult = fallbackSingle(task.getSelfId(), task.getVisitedId());
                }
                completeTaskFuture(task, taskResult, responseItem != null ? "batch" : "batch-missing-fallback");
            }
            aggregateBatchSuccessCount.incrementAndGet();
            log.info("user info aggregate batch success, batchSize={}, fallbackCount={}, cost={}ms, queueSize={}, completeCount={}, skippedCount={}",
                    activeTasks.size(),
                    fallbackCount,
                    System.currentTimeMillis() - startTime,
                    taskQueue == null ? -1 : taskQueue.size(),
                    aggregateCompletedCount.get(),
                    aggregateCompleteSkippedCount.get());
        } catch (Exception e) {
            aggregateBatchFailureCount.incrementAndGet();
            log.error("flush user info aggregate batch failed, batchSize={}", activeTasks.size(), e);
            writeFallbackBatch(activeTasks, e);
            for (UserInfoAggregateTask task : activeTasks) {
                Result<UserInfoBatchResponseItem> fallbackResult = fallbackSingle(task.getSelfId(), task.getVisitedId());
                completeTaskFuture(task, fallbackResult, "batch-error-fallback");
            }
        }
    }

    private Result<UserInfoBatchResponseItem> fallbackSingle(Integer selfId, Integer visitedId) {
        Result<UserInfoBatchResponseItem> result = userInfoClient.getUserInfo(selfId, visitedId);
        if (result == null) {
            return Result.error("查询用户信息失败");
        }
        return result;
    }

    private void writeFallbackBatch(List<UserInfoAggregateTask> tasks, Exception exception) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("time", new Date().getTime());
        payload.put("message", exception == null ? null : exception.getMessage());
        payload.put("retryCount", 0);
        List<Map<String, Object>> taskList = new ArrayList<>();
        for (UserInfoAggregateTask task : tasks) {
            Map<String, Object> taskMap = new HashMap<>();
            taskMap.put("requestId", task.getRequestId());
            taskMap.put("selfId", task.getSelfId());
            taskMap.put("visitedId", task.getVisitedId());
            taskList.add(taskMap);
        }
        payload.put("tasks", taskList);
        String payloadJson = JSON.toJSONString(payload);
        redisTemplate.opsForList().rightPush(FALLBACK_BATCH_KEY, payloadJson == null ? "{}" : payloadJson);
    }

    @SuppressWarnings("null")
    private Long incrementWindow(String key, Long expireSeconds) {
        if (key == null || expireSeconds == null) {
            return null;
        }
        try {
            return redisTemplate.execute(incrementWindowScript, Collections.singletonList(key), String.valueOf(expireSeconds));
        } catch (Exception e) {
            log.warn("execute increment window script failed, key={}", key, e);
            return null;
        }
    }

    private void completeTaskFuture(UserInfoAggregateTask task, Result<UserInfoBatchResponseItem> result, String source) {
        if (task == null || task.getFuture() == null) {
            return;
        }
        boolean completed = task.getFuture().complete(result);
        if (completed) {
            aggregateCompletedCount.incrementAndGet();
            if (!"batch".equals(source)) {
                log.info("user info aggregate future completed by {}, requestId={}, visitedId={}", source, task.getRequestId(), task.getVisitedId());
            }
            if ("timeout-fallback".equals(source) || "batch-error-fallback".equals(source) || "batch-missing-fallback".equals(source)) {
                aggregateFutureCompletedByFallbackCount.incrementAndGet();
            }
        } else {
            aggregateCompleteSkippedCount.incrementAndGet();
            log.debug("skip duplicate future completion, source={}, requestId={}, visitedId={}", source, task.getRequestId(), task.getVisitedId());
        }
    }

    private String buildMinuteSlot() {
        long minutes = System.currentTimeMillis() / 60000;
        return String.valueOf(minutes);
    }
}
