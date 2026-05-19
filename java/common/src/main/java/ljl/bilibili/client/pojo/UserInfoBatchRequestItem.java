package ljl.bilibili.client.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UserInfoBatchRequestItem {
    private String requestId;
    private Integer selfId;
    private Integer visitedId;
}
