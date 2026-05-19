package ljl.bilibili.gateway.service.userinfo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import ljl.bilibili.client.pojo.UserInfoBatchRequest;
import ljl.bilibili.client.pojo.UserInfoBatchRequestItem;
import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.client.user_center.UserInfoClient;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class UserInfoFallbackCompensationService implements InitializingBean, DisposableBean {

    private static final String FALLBACK_BATCH_KEY = "gateway:userInfo:fallback:batch";
    private static final String DEADLETTER_KEY = "gateway:userInfo:deadletter";

    @Resource
    private RedisTemplate<String, String> redisTemplate;
    @Resource
    private UserInfoClient userInfoClient;

    @Value("${user-info-aggregate.compensation-enabled:true}")
    private Boolean enabled;
    @Value("${user-info-aggregate.compensation-poll-interval-ms:5000}")
    private Long pollIntervalMs;
    @Value("${user-info-aggregate.compensation-max-retry:3}")
    private Integer maxRetry;

    private final AtomicLong compensationConsumeCount = new AtomicLong();
    private final AtomicLong compensationSuccessCount = new AtomicLong();
    private final AtomicLong compensationRequeueCount = new AtomicLong();
    private final AtomicLong compensationDeadletterCount = new AtomicLong();
    private final AtomicLong compensationParseFailureCount = new AtomicLong();

    private volatile boolean running;
    private Thread workerThread;

    @Override
    public void afterPropertiesSet() {
        this.running = true;
        this.workerThread = new Thread(this::consumeLoop, "user-info-fallback-compensation-worker");
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

    private void consumeLoop() {
        while (running) {
            try {
                if (!Boolean.TRUE.equals(enabled)) {
                    sleepQuietly();
                    continue;
                }
                String payloadJson = redisTemplate.opsForList().leftPop(FALLBACK_BATCH_KEY);
                if (payloadJson == null) {
                    sleepQuietly();
                    continue;
                }
                compensationConsumeCount.incrementAndGet();
                handlePayload(payloadJson);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("fallback compensation worker failed", e);
                try {
                    sleepQuietly();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handlePayload(String payloadJson) {
        Map<String, Object> payload = JSON.parseObject(payloadJson, new TypeReference<Map<String, Object>>() {});
        if (payload == null) {
            compensationParseFailureCount.incrementAndGet();
            log.warn("fallback compensation payload parse result is null");
            return;
        }
        Integer retryCount = getInteger(payload.get("retryCount"));
        if (retryCount == null) {
            retryCount = 0;
        }
        List<Map<String, Object>> tasks = getTaskList(payload.get("tasks"));
        if (tasks.isEmpty()) {
            compensationParseFailureCount.incrementAndGet();
            log.warn("fallback compensation task list is empty, retryCount={}", retryCount);
            return;
        }

        UserInfoBatchRequest request = new UserInfoBatchRequest().setItems(new ArrayList<>());
        for (Map<String, Object> task : tasks) {
            request.getItems().add(new UserInfoBatchRequestItem()
                    .setRequestId(getString(task.get("requestId")))
                    .setSelfId(getInteger(task.get("selfId")))
                    .setVisitedId(getInteger(task.get("visitedId"))));
        }

        try {
            Result<List<UserInfoBatchResponseItem>> result = userInfoClient.getUserInfoBatch(request);
            if (result != null && result.getData() != null) {
                compensationSuccessCount.incrementAndGet();
                log.info("fallback batch compensation success, size={}, retryCount={}, consumeCount={}, successCount={}",
                        result.getData().size(),
                        retryCount,
                        compensationConsumeCount.get(),
                        compensationSuccessCount.get());
                return;
            }
            requeueOrDeadLetter(payload, retryCount, "empty compensation result");
        } catch (Exception e) {
            requeueOrDeadLetter(payload, retryCount, e.getMessage());
        }
    }

    private void requeueOrDeadLetter(Map<String, Object> payload, Integer retryCount, String message) {
        int nextRetry = retryCount == null ? 1 : retryCount + 1;
        payload.put("retryCount", nextRetry);
        payload.put("message", message);
        payload.put("lastRetryTime", new Date().getTime());
        String json = JSON.toJSONString(payload);
        String safeJson = json == null ? "{}" : json;
        if (nextRetry >= maxRetry) {
            compensationDeadletterCount.incrementAndGet();
            redisTemplate.opsForList().rightPush(DEADLETTER_KEY, safeJson);
            log.warn("fallback batch moved to deadletter, retryCount={}, deadletterCount={}", nextRetry, compensationDeadletterCount.get());
        } else {
            compensationRequeueCount.incrementAndGet();
            redisTemplate.opsForList().rightPush(FALLBACK_BATCH_KEY, safeJson);
            log.warn("fallback batch requeued, retryCount={}, requeueCount={}", nextRetry, compensationRequeueCount.get());
        }
    }

    private List<Map<String, Object>> getTaskList(Object tasksObject) {
        if (tasksObject == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseObject(JSON.toJSONString(tasksObject), new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            compensationParseFailureCount.incrementAndGet();
            log.warn("parse fallback tasks failed", e);
            return Collections.emptyList();
        }
    }

    private Integer getInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void sleepQuietly() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(pollIntervalMs == null || pollIntervalMs <= 0 ? 5000L : pollIntervalMs);
    }
}
