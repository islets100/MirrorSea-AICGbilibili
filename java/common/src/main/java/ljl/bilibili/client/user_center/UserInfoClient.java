package ljl.bilibili.client.user_center;

import ljl.bilibili.client.pojo.UserInfoBatchRequest;
import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.util.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Component
@FeignClient(name = "user_center", url = "http://localhost:3000")
public interface UserInfoClient {
    @PostMapping("/userInfo/getUserInfoBatch")
    Result<List<UserInfoBatchResponseItem>> getUserInfoBatch(@RequestBody UserInfoBatchRequest request);

    @GetMapping("/userInfo/getUserInfoSimple/{selfId}/{visitedId}")
    Result<UserInfoBatchResponseItem> getUserInfo(@PathVariable("selfId") Integer selfId, @PathVariable("visitedId") Integer visitedId);
}
