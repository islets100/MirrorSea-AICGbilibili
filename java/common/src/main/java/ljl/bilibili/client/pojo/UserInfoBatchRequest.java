package ljl.bilibili.client.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class UserInfoBatchRequest {
    private List<UserInfoBatchRequestItem> items;
}
