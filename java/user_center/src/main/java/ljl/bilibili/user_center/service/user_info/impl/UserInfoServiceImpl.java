package ljl.bilibili.user_center.service.user_info.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import ljl.bilibili.client.notice.SendNoticeClient;
import ljl.bilibili.client.pojo.UserInfoBatchRequest;
import ljl.bilibili.client.pojo.UserInfoBatchRequestItem;
import ljl.bilibili.client.pojo.UserInfoBatchResponseItem;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.entity.user_center.user_relationships.Follow;
import ljl.bilibili.entity.user_center.user_relationships.IdCount;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.mapper.user_center.user_relationships.FollowMapper;
import ljl.bilibili.user_center.mapper.UserCenterServiceMapper;
import ljl.bilibili.user_center.service.user_info.UserInfoService;
import ljl.bilibili.user_center.vo.response.self_center.UserInfoResponse;
import ljl.bilibili.util.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static ljl.bilibili.user_center.constant.Constant.*;

@Service
public class UserInfoServiceImpl implements UserInfoService {

    @Resource
    UserMapper userMapper;
    @Resource
    FollowMapper followMapper;
    @Resource
    MinioClient minioClient;
    @Value("${minio.bucket.name}")
    private String bucketName;
    String filePath = "https://labilibili.com/";
    @Resource
    SendNoticeClient sendNoticeClient;
    @Resource
    UserCenterServiceMapper userCenterServiceMapper;

    @Override
    public Result<UserInfoResponse> getUserInfo(Integer selfId, Integer visitedId) {
        MPJLambdaWrapper<User> fansCountWrapper = new MPJLambdaWrapper<>();
        MPJLambdaWrapper<User> idolCountWrapper = new MPJLambdaWrapper<>();
        fansCountWrapper.eq(User::getId, visitedId);
        fansCountWrapper.leftJoin(Follow.class, Follow::getIdolId, User::getId);
        idolCountWrapper.eq(User::getId, visitedId);
        idolCountWrapper.leftJoin(Follow.class, Follow::getFansId, User::getId);
        UserInfoResponse userInfoResponse = userCenterServiceMapper.getUserInfo(visitedId)
                .setFansCount(Math.toIntExact(userMapper.selectJoinCount(fansCountWrapper)))
                .setIdolCount(Math.toIntExact(userMapper.selectJoinCount(idolCountWrapper)));
        if (selfId > 0) {
            LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
            followLambdaQueryWrapper.eq(Follow::getFansId, selfId);
            followLambdaQueryWrapper.eq(Follow::getIdolId, visitedId);
            userInfoResponse.setIsFollowing(followMapper.selectList(followLambdaQueryWrapper).size() > 0);
        } else {
            userInfoResponse.setIsFollowing(false);
        }
        normalizeUserInfoResponse(userInfoResponse);
        return Result.data(userInfoResponse);
    }

    @Override
    public Result<List<UserInfoBatchResponseItem>> getUserInfoBatch(UserInfoBatchRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            return Result.data(new ArrayList<>());
        }
        List<UserInfoBatchRequestItem> requestItems = request.getItems().stream()
                .filter(item -> item != null && item.getVisitedId() != null)
                .collect(Collectors.toList());
        if (requestItems.isEmpty()) {
            return Result.data(new ArrayList<>());
        }

        Set<Integer> visitedIdSet = requestItems.stream()
                .map(UserInfoBatchRequestItem::getVisitedId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Integer> visitedIds = new ArrayList<>(visitedIdSet);

        Map<Integer, UserInfoResponse> baseInfoMap = userCenterServiceMapper.getUserInfoBatch(visitedIds).stream()
                .peek(this::normalizeUserInfoResponse)
                .collect(Collectors.toMap(UserInfoResponse::getId, response -> response, (left, right) -> left));

        Map<Integer, Integer> fansCountMap = toCountMap(followMapper.getFansCount(visitedIds));
        Map<Integer, Integer> idolCountMap = toCountMap(followMapper.getIdolCount(visitedIds));

        for (Integer visitedId : visitedIds) {
            UserInfoResponse userInfoResponse = baseInfoMap.get(visitedId);
            if (userInfoResponse != null) {
                userInfoResponse.setFansCount(fansCountMap.getOrDefault(visitedId, 0));
                userInfoResponse.setIdolCount(idolCountMap.getOrDefault(visitedId, 0));
                normalizeUserInfoResponse(userInfoResponse);
            }
        }

        Map<Integer, Set<Integer>> followingMap = buildFollowingMap(requestItems);
        List<UserInfoBatchResponseItem> responseItems = new ArrayList<>(requestItems.size());
        for (UserInfoBatchRequestItem requestItem : requestItems) {
            UserInfoResponse baseInfo = baseInfoMap.get(requestItem.getVisitedId());
            UserInfoResponse userInfoResponse = copyUserInfo(baseInfo, requestItem.getVisitedId());
            if (requestItem.getSelfId() != null && requestItem.getSelfId() > 0) {
                userInfoResponse.setIsFollowing(followingMap
                        .getOrDefault(requestItem.getSelfId(), new HashSet<>())
                        .contains(requestItem.getVisitedId()));
            } else {
                userInfoResponse.setIsFollowing(false);
            }
            normalizeUserInfoResponse(userInfoResponse);
            responseItems.add(toBatchResponseItem(requestItem.getRequestId(), requestItem.getSelfId(), requestItem.getVisitedId(), userInfoResponse));
        }
        return Result.data(responseItems);
    }

    @Override
    public Result<UserInfoBatchResponseItem> getUserInfoSimple(Integer selfId, Integer visitedId) {
        Result<UserInfoResponse> result = getUserInfo(selfId, visitedId);
        UserInfoResponse response = result.getData();
        return Result.data(toBatchResponseItem(null, selfId, visitedId, response));
    }

    /**
     *修改用户信息并发送数据同步消息
     */
    @Override
    public Result<Boolean> editSelfInfo(MultipartFile file, Integer userId, String nickname, String intro) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        Map<String, Object> map = new HashMap<>();
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(User::getId, userId);
        map.put(TABLE_ID, userId);
        map.put(OPERATION_TYPE, OPERATION_TYPE_UPDATE);
        map.put(TABLE_NAME, USER_TABLE_NAME);
        if (file != null) {
            String coverName = UUID.randomUUID().toString().substring(0, 10) + file.getOriginalFilename();
            minioClient.putObject(PutObjectArgs.builder().contentType(file.getContentType()).stream(file.getInputStream(), -1, 10485760).bucket(bucketName).object(coverName).build());
            String url = filePath + bucketName + "/" + coverName;
            map.put(USER_COVER, url);
            wrapper.set(User::getCover, url);
        }
        if (nickname != null) {
            map.put(USER_NICKNAME, nickname);
            wrapper.set(User::getNickname, nickname);
        }
        if (intro != null) {
            map.put(USER_INTRO, intro);
            wrapper.set(User::getIntro, intro);
        }
        userMapper.update(null, wrapper);
        sendNoticeClient.sendDBChangeNotice(map);
        return Result.success(true);
    }

    private Map<Integer, Integer> toCountMap(List<IdCount> counts) {
        Map<Integer, Integer> countMap = new HashMap<>();
        if (counts == null) {
            return countMap;
        }
        for (IdCount idCount : counts) {
            if (idCount != null && idCount.getId() != null) {
                countMap.put(idCount.getId(), idCount.getCount() == null ? 0 : idCount.getCount());
            }
        }
        return countMap;
    }

    private Map<Integer, Set<Integer>> buildFollowingMap(List<UserInfoBatchRequestItem> requestItems) {
        Map<Integer, Set<Integer>> followingMap = new HashMap<>();
        Map<Integer, List<Integer>> selfVisitedMap = requestItems.stream()
                .filter(item -> item.getSelfId() != null && item.getSelfId() > 0)
                .collect(Collectors.groupingBy(UserInfoBatchRequestItem::getSelfId,
                        Collectors.mapping(UserInfoBatchRequestItem::getVisitedId,
                                Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new))));
        for (Map.Entry<Integer, List<Integer>> entry : selfVisitedMap.entrySet()) {
            List<Integer> followingVisitedIds = followMapper.getFollowingVisitedIds(entry.getKey(), entry.getValue());
            followingMap.put(entry.getKey(), new HashSet<>(followingVisitedIds));
        }
        return followingMap;
    }

    private UserInfoResponse copyUserInfo(UserInfoResponse source, Integer visitedId) {
        if (source == null) {
            return new UserInfoResponse()
                    .setId(visitedId)
                    .setFansCount(0)
                    .setIdolCount(0)
                    .setLikeCount(0)
                    .setPlayCount(0)
                    .setIsFollowing(false);
        }
        return new UserInfoResponse()
                .setId(source.getId())
                .setCover(source.getCover())
                .setNickname(source.getNickname())
                .setIntro(source.getIntro())
                .setFansCount(source.getFansCount())
                .setIdolCount(source.getIdolCount())
                .setLikeCount(source.getLikeCount())
                .setPlayCount(source.getPlayCount())
                .setIsFollowing(Boolean.TRUE.equals(source.getIsFollowing()));
    }

    private UserInfoBatchResponseItem toBatchResponseItem(String requestId, Integer selfId, Integer visitedId, UserInfoResponse response) {
        UserInfoResponse normalized = copyUserInfo(response, visitedId);
        normalizeUserInfoResponse(normalized);
        return new UserInfoBatchResponseItem()
                .setRequestId(requestId)
                .setSelfId(selfId)
                .setVisitedId(visitedId)
                .setId(normalized.getId())
                .setCover(normalized.getCover())
                .setNickname(normalized.getNickname())
                .setIntro(normalized.getIntro())
                .setFansCount(normalized.getFansCount())
                .setIdolCount(normalized.getIdolCount())
                .setLikeCount(normalized.getLikeCount())
                .setPlayCount(normalized.getPlayCount())
                .setIsFollowing(normalized.getIsFollowing());
    }

    private void normalizeUserInfoResponse(UserInfoResponse response) {
        if (response == null) {
            return;
        }
        if (response.getFansCount() == null) {
            response.setFansCount(0);
        }
        if (response.getIdolCount() == null) {
            response.setIdolCount(0);
        }
        if (response.getLikeCount() == null) {
            response.setLikeCount(0);
        }
        if (response.getPlayCount() == null) {
            response.setPlayCount(0);
        }
        if (response.getIsFollowing() == null) {
            response.setIsFollowing(false);
        }
    }
}
