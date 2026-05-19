package ljl.bilibili.gateway.filter;

import com.alibaba.fastjson.JSON;
import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.gateway.service.userinfo.UserInfoAggregateService;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class UserInfoAggregateFilter implements WebFilter {

    private static final String USER_INFO_PREFIX = "/userInfo/getUserInfo/";

    @Resource
    private UserInfoAggregateService userInfoAggregateService;

    @Override
    @NonNull
    @SuppressWarnings("null")
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        if (!HttpMethod.GET.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(USER_INFO_PREFIX)) {
            return chain.filter(exchange);
        }
        Integer[] ids = parsePathIds(path);
        if (ids == null) {
            return chain.filter(exchange);
        }
        Integer selfId = ids[0];
        Integer visitedId = ids[1];
        if (!userInfoAggregateService.shouldAggregate(visitedId)) {
            return chain.filter(exchange);
        }
        Result<UserInfoBatchResponseItem> result = userInfoAggregateService.getAggregatedOrFallback(selfId, visitedId);
        return writeJson(exchange, result);
    }

    private Integer[] parsePathIds(String path) {
        String suffix = path.substring(USER_INFO_PREFIX.length());
        String[] segments = suffix.split("/");
        if (segments.length != 2) {
            return null;
        }
        try {
            return new Integer[]{Integer.valueOf(segments[0]), Integer.valueOf(segments[1])};
        } catch (NumberFormatException e) {
            log.warn("parse user info path failed, path={}", path, e);
            return null;
        }
    }

    @SuppressWarnings("null")
    private Mono<Void> writeJson(ServerWebExchange exchange, Result<UserInfoBatchResponseItem> result) {
        String json = JSON.toJSONString(result);
        final byte[] bytes = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Flux.just(buffer));
    }
}
