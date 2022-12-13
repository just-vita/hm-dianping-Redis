package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 设置用户信息
            setBlogUser(blog);
            // 设置是否点赞
            setBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        setBlogUser(blog);
        setBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + id;
        // 查询sortedset是否存在此用户
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null){
            // 已点赞过，取消点赞，将笔记的点赞数 - 1
            boolean isSuccess = lambdaUpdate()
                    .setSql("liked = liked - 1")
                    .eq(Blog::getId, id)
                    .update();
            if (isSuccess) {
                // 将用户移出点赞集合
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }else{
            // 未点赞过，点赞，将笔记的点赞数 + 1
            boolean isSuccess = lambdaUpdate()
                    .setSql("liked = liked + 1")
                    .eq(Blog::getId, id)
                    .update();
            if (isSuccess){
                // 将用户加入点赞集合
                // 使用时间戳作为score的排序规则
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }

        }

        return Result.ok();
    }

    @Override
    public Result queryLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 得到用户ID列表
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idsStr = StrUtil.join(",", ids);
        // 查询已排序的用户列表
        List<User> sortedUserList = userService.lambdaQuery()
                .in(User::getId, ids)
                .last("ORDER BY FIELD(id," + idsStr + ")")
                .list();
        List<UserDTO> userDTOList = BeanUtil.copyToList(sortedUserList, UserDTO.class);
        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("保存失败");
        }

        List<Follow> followList = followService.lambdaQuery()
                .eq(Follow::getFollowUserId, userId)
                .list();
        // 推送笔记ID给所有粉丝，存入粉丝的收件箱（redis）中
        for (Follow follow : followList) {
            Long followUserId = follow.getUserId();
            String key = FEED_KEY + followUserId;
            stringRedisTemplate.opsForZSet().add(key,
                    blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;
        // 参数顺序：key 最小值(默认0) 最大值 偏移量 查询数量(此处默认一次查2个)
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 指定长度为结果集的长度，以免触发自动扩容影响性能
        List<Long> ids = new ArrayList<>(typedTuples.size());
        // 最小时间戳 控制从何处开始查询 此处代表查询出的数据的最后一对值
        long minTime = 0;
        // 偏移量 用来跳过重复值
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String blogId = typedTuple.getValue();
            ids.add(Long.valueOf(blogId));
            long timestamp = typedTuple.getScore().longValue();
            if (minTime == timestamp){
                // 最后一个是重复值，偏移量加一
                os++;
            }else{
                // 不是重复值，重置偏移量
                minTime = timestamp;
                os = 1;
            }
        }
        String idsStr = StrUtil.join(",", ids);
        List<Blog> blogList = lambdaQuery()
                .in(Blog::getId, ids)
                .last("ORDER BY FIELD(id," + idsStr + ")")
                .list();
        for (Blog blog : blogList) {
            setBlogUser(blog);
            setBlogLiked(blog);
        }
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogList);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);

        return Result.ok(scrollResult);
    }

    private void setBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void setBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        /**
         * 此处报错 WRONGTYPE Operation against a key holding the wrong kind of value
         * 原因是：redis中已存储了同名但不同类型的数据
         */
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }
}
