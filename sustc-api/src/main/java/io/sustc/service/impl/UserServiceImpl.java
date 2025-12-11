package io.sustc.service.impl;

import io.sustc.dto.*;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class UserServiceImpl implements UserService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证认证信息：用户存在且未删除
     */
    private void checkAuth(AuthInfo auth) {
        if (auth == null) {
            throw new SecurityException("Auth info is null.");
        }
        // 只验证用户存在且活跃，不验证密码（符合文档要求）
        String sql = "SELECT is_deleted FROM users WHERE id = ?";
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(sql, Boolean.class, auth.getAuthorId());
            if (isDeleted == null || isDeleted) {
                throw new SecurityException("User is deleted or does not exist.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new SecurityException("User does not exist.");
        }
    }

    /**
     * 验证用户是否是目标用户本人
     */
    private void checkSelfOperation(AuthInfo auth, long targetUserId) {
        if (auth.getAuthorId() != targetUserId) {
            throw new SecurityException("Can only operate on your own account.");
        }
    }

    /**
     * 验证用户是否存在
     */
    private void checkUserExists(long userId) {
        String sql = "SELECT 1 FROM users WHERE id = ?";
        try {
            jdbcTemplate.queryForObject(sql, Integer.class, userId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("User not found.");
        }
    }

    @Override
    @Transactional
    public long register(RegisterUserReq req) {
        // 验证必填字段
        if (req.getName() == null || req.getName().trim().isEmpty()) {
            return -1;
        }

        // 验证性别
        String genderStr = null;
        if (req.getGender() != null) {
            switch (req.getGender()) {
                case MALE:
                    genderStr = "Male";
                    break;
                case FEMALE:
                    genderStr = "Female";
                    break;
                case UNKNOWN:
                    genderStr = "Unknown";
                    break;
                default:
                    return -1;
            }
        }

        // 验证生日（年龄）
        if (req.getBirthday() == null || req.getBirthday().isEmpty()) {
            return -1;
        }
        int age = calculateAge(req.getBirthday());
        if (age <= 0) {
            return -1;
        }

        // 检查用户名是否已存在
        String checkSql = "SELECT COUNT(*) FROM users WHERE name = ?";
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, req.getName());
        if (count != null && count > 0) {
            return -1; // 用户名已存在
        }

        // 生成新用户ID
        Long newId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM users", Long.class);

        // 插入用户（密码需要哈希存储，这里为了兼容测试数据使用明文）
        String sql = "INSERT INTO users (id, name, password, gender, age, is_deleted) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, newId, req.getName(), req.getPassword(), genderStr, age, false);

        log.info("User registered: id={}, name={}", newId, req.getName());
        return newId;
    }

    /**
     * 根据生日字符串计算年龄（简化实现）
     */
    private int calculateAge(String birthday) {
        try {
            // 假设格式为 yyyy-MM-dd
            String[] parts = birthday.split("-");
            if (parts.length >= 3) {
                int birthYear = Integer.parseInt(parts[0]);
                int currentYear = java.time.Year.now().getValue();
                return currentYear - birthYear;
            }
        } catch (Exception e) {
            log.warn("Failed to parse birthday: {}", birthday);
        }
        return -1;
    }

    @Override
    public long login(AuthInfo auth) {
        if (auth == null || auth.getPassword() == null || auth.getPassword().isEmpty()) {
            return -1;
        }

        String sql = "SELECT id, password, is_deleted FROM users WHERE id = ?";
        try {
            Map<String, Object> user = jdbcTemplate.queryForMap(sql, auth.getAuthorId());
            if (user == null) {
                return -1;
            }

            boolean isDeleted = (Boolean) user.get("is_deleted");
            if (isDeleted) {
                return -1; // 用户已被软删除
            }

            String storedPassword = (String) user.get("password");
            // 简单密码验证（实际应使用哈希比对）
            if (!auth.getPassword().equals(storedPassword)) {
                return -1; // 密码不匹配
            }

            return auth.getAuthorId(); // 登录成功
        } catch (EmptyResultDataAccessException e) {
            return -1; // 用户不存在
        }
    }

    @Override
    @Transactional
    public boolean deleteAccount(AuthInfo auth, long userId) {
        checkAuth(auth);
        checkSelfOperation(auth, userId);

        // 检查用户是否存在且未被删除
        String checkSql = "SELECT is_deleted FROM users WHERE id = ?";
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(checkSql, Boolean.class, userId);
            if (isDeleted == null) {
                throw new IllegalArgumentException("User not found.");
            }
            if (isDeleted) {
                return false; // 已删除
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("User not found.");
        }

        // 软删除用户
        jdbcTemplate.update("UPDATE users SET is_deleted = TRUE WHERE id = ?", userId);

        // 清除关注关系（双向）
        jdbcTemplate.update("DELETE FROM user_follows WHERE follower_id = ?", userId);
        jdbcTemplate.update("DELETE FROM user_follows WHERE followee_id = ?", userId);

        log.info("User soft-deleted: id={}", userId);
        return true;
    }

    @Override
    @Transactional
    public boolean follow(AuthInfo auth, long followeeId) {
        checkAuth(auth);

        // 不能关注自己
        if (auth.getAuthorId() == followeeId) {
            throw new SecurityException("Cannot follow yourself.");
        }

        // 检查目标用户是否存在
        checkUserExists(followeeId);

        // 检查是否已关注
        String checkSql = "SELECT 1 FROM user_follows WHERE follower_id = ? AND followee_id = ?";
        try {
            jdbcTemplate.queryForObject(checkSql, Integer.class, auth.getAuthorId(), followeeId);
            // 已关注，执行取消关注
            jdbcTemplate.update("DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                    auth.getAuthorId(), followeeId);
            return false; // 取消关注后状态为"未关注"
        } catch (EmptyResultDataAccessException e) {
            // 未关注，执行关注
            jdbcTemplate.update("INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)",
                    auth.getAuthorId(), followeeId);
            return true; // 关注成功
        }
    }

    @Override
    public UserRecord getById(long userId) {
        String sql = "SELECT id, name, gender, age, password, is_deleted FROM users WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, new UserRowMapper(), userId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("User not found.");
        }
    }

    @Override
    @Transactional
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        checkAuth(auth);

        // 验证性别
        String genderStr = null;
        if (gender != null) {
            if (!"Male".equalsIgnoreCase(gender) && !"Female".equalsIgnoreCase(gender)) {
                throw new IllegalArgumentException("Invalid gender.");
            }
            genderStr = gender;
        }

        // 验证年龄
        if (age != null && age <= 0) {
            throw new IllegalArgumentException("Age must be positive.");
        }

        // 构建动态更新SQL
        StringBuilder sqlBuilder = new StringBuilder("UPDATE users SET ");
        List<Object> params = new ArrayList<>();
        boolean needsUpdate = false;

        if (genderStr != null) {
            sqlBuilder.append("gender = ?, ");
            params.add(genderStr);
            needsUpdate = true;
        }

        if (age != null) {
            sqlBuilder.append("age = ?, ");
            params.add(age);
            needsUpdate = true;
        }

        if (needsUpdate) {
            // 去掉最后的逗号和空格
            sqlBuilder.setLength(sqlBuilder.length() - 2);
            sqlBuilder.append(" WHERE id = ?");
            params.add(auth.getAuthorId());
            jdbcTemplate.update(sqlBuilder.toString(), params.toArray());
        }
    }

    @Override
    public PageResult<FeedItem> feed(AuthInfo auth, int page, int size, String category) {
        checkAuth(auth);

        // 调整分页参数范围
        if (page < 1) page = 1;
        if (size < 1) size = 1;
        if (size > 200) size = 200;

        // 构建基础SQL
        StringBuilder sqlBuilder = new StringBuilder(
                "SELECT r.id as recipe_id, r.name as recipe_name, r.author_id, u.name as author_name, " +
                        "r.date_published, r.aggregated_rating, r.review_count " +
                        "FROM recipes r " +
                        "JOIN users u ON r.author_id = u.id " +
                        "JOIN user_follows uf ON r.author_id = uf.followee_id " +
                        "WHERE uf.follower_id = ? AND r.is_deleted = FALSE "
        );
        List<Object> params = new ArrayList<>();
        params.add(auth.getAuthorId());

        // 添加分类过滤
        if (category != null && !category.isEmpty()) {
            sqlBuilder.append("AND r.category = ? ");
            params.add(category);
        }

        // 获取总数
        String countSql = "SELECT COUNT(*) FROM (" + sqlBuilder.toString() + ") AS temp";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) total = 0L;

        // 添加排序（按发布时间倒序，日期相同时按recipe_id倒序）
        sqlBuilder.append("ORDER BY r.date_published DESC, r.id DESC ");

        // 分页
        sqlBuilder.append("LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);

        List<FeedItem> items = jdbcTemplate.query(
                sqlBuilder.toString(),
                (rs, rowNum) -> FeedItem.builder()
                        .recipeId(rs.getLong("recipe_id"))
                        .name(rs.getString("recipe_name"))
                        .authorId(rs.getLong("author_id"))
                        .authorName(rs.getString("author_name"))
                        .datePublished(rs.getTimestamp("date_published").toInstant())
                        .aggregatedRating(rs.getDouble("aggregated_rating"))
                        .reviewCount(rs.getInt("review_count"))
                        .build(),
                params.toArray()
        );

        PageResult<FeedItem> result = new PageResult<>();
        result.setItems(items);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        return result;
    }

    @Override
    public Map<String, Object> getUserWithHighestFollowRatio() {
        String sql = "SELECT " +
                "u.id as author_id, " +
                "u.name as author_name, " +
                "COUNT(DISTINCT fl1.follower_id) as follower_count, " +
                "COUNT(DISTINCT fl2.followee_id) as following_count " +
                "FROM users u " +
                "LEFT JOIN user_follows fl1 ON u.id = fl1.followee_id " +
                "LEFT JOIN user_follows fl2 ON u.id = fl2.follower_id " +
                "WHERE u.is_deleted = FALSE " +
                "GROUP BY u.id, u.name " +
                "HAVING COUNT(DISTINCT fl2.followee_id) > 0 " +  // FollowingCount > 0
                "ORDER BY (COUNT(DISTINCT fl1.follower_id) * 1.0 / COUNT(DISTINCT fl2.followee_id)) DESC, " +
                "u.id ASC " +
                "LIMIT 1";

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> map = new HashMap<>();
                map.put("AuthorId", rs.getLong("author_id"));
                map.put("AuthorName", rs.getString("author_name"));
                // 计算比率
                double followerCount = rs.getLong("follower_count");
                double followingCount = rs.getLong("following_count");
                map.put("Ratio", followerCount / followingCount);
                return map;
            });
        } catch (EmptyResultDataAccessException e) {
            return null; // 没有符合条件的用户
        }
    }

    /**
     * RowMapper for UserRecord
     */
    private static class UserRowMapper implements RowMapper<UserRecord> {
        @Override
        public UserRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            UserRecord user = new UserRecord();
            user.setAuthorId(rs.getLong("id"));
            user.setAuthorName(rs.getString("name"));
            user.setGender(rs.getString("gender"));
            user.setAge(rs.getInt("age"));
            user.setPassword(rs.getString("password"));
            user.setDeleted(rs.getBoolean("is_deleted"));

            // followers 和 following 需要从 user_follows 表动态计算
            // 这里可以扩展查询，但为了性能，建议在需要时单独查询
            return user;
        }
    }
}