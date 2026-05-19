package ljl.bilibili.client.pojo;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class UserInfoBatchResponseItem {
    private String requestId;
    private Integer selfId;
    private Integer visitedId;
    private Integer id;
    private String cover;
    private String nickname;
    private String intro;
    private Integer idolCount;
    private Integer fansCount;
    private Integer likeCount;
    private Integer playCount;
    private Boolean isFollowing;
}
