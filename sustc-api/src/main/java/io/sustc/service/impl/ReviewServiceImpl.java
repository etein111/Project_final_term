package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.dto.ReviewRecord;
import io.sustc.service.ReviewService;
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
import java.util.List;
import java.time.Duration;

@Service
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证认证信息：用户存在且未删除
     */
    private void checkAuth(AuthInfo auth) {
        if (auth == null) {
            throw new SecurityException("Auth info is null.");
        }
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
     * 验证用户是否是评论的作者
     */
    private void checkReviewOwnership(AuthInfo auth, long reviewId) {
        String sql = "SELECT author_id FROM reviews WHERE id = ?";
        try {
            Long authorId = jdbcTemplate.queryForObject(sql, Long.class, reviewId);
            if (authorId == null || authorId != auth.getAuthorId()) {
                throw new SecurityException("You are not the author of this review.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review not found.");
        }
    }

    /**
     * 验证recipe是否存在且未被删除
     */
    private void checkRecipeExists(long recipeId) {
        String sql = "SELECT is_deleted FROM recipes WHERE id = ?";
        try {
            Boolean isDeleted = jdbcTemplate.queryForObject(sql, Boolean.class, recipeId);
            if (isDeleted == null) {
                throw new IllegalArgumentException("Recipe not found.");
            }
            if (isDeleted) {
                throw new IllegalArgumentException("Recipe has been deleted.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe not found.");
        }
    }

    /**
     * 验证review是否属于指定的recipe
     */
    private void checkReviewBelongsToRecipe(long reviewId, long recipeId) {
        String sql = "SELECT recipe_id FROM reviews WHERE id = ?";
        try {
            Long actualRecipeId = jdbcTemplate.queryForObject(sql, Long.class, reviewId);
            if (actualRecipeId == null || actualRecipeId != recipeId) {
                throw new IllegalArgumentException("Review does not belong to the specified recipe.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review not found.");
        }
    }

    /**
     * 更新食谱的聚合评分和评论数
     * 如果没有评论，aggregated_rating设为null，review_count设为0
     */
    private void updateRecipeRating(long recipeId) {
        String sql = "UPDATE recipes SET " +
                "aggregated_rating = (SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE recipe_id = ?), " +
                "review_count = (SELECT COUNT(*) FROM reviews WHERE recipe_id = ?) " +
                "WHERE id = ?";
        jdbcTemplate.update(sql, recipeId, recipeId, recipeId);
    }

    @Override
    @Transactional
    public long addReview(AuthInfo auth, long recipeId, int rating, String review) {
        checkAuth(auth);
        checkRecipeExists(recipeId);

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5.");
        }

        Long newId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM reviews", Long.class);
        Timestamp now = Timestamp.from(Instant.now());

        String sql = "INSERT INTO reviews (id, recipe_id, author_id, rating, content, date_submitted, date_modified) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, newId, recipeId, auth.getAuthorId(), rating, review, now, now);

        updateRecipeRating(recipeId);

        return newId;
    }

    @Override
    @Transactional
    public void editReview(AuthInfo auth, long recipeId, long reviewId, int rating, String review) {
        checkAuth(auth);
        checkReviewBelongsToRecipe(reviewId, recipeId);
        checkReviewOwnership(auth, reviewId);

        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5.");
        }

        Timestamp now = Timestamp.from(Instant.now());
        String sql = "UPDATE reviews SET rating = ?, content = ?, date_modified = ? WHERE id = ?";
        jdbcTemplate.update(sql, rating, review, now, reviewId);

        updateRecipeRating(recipeId);
    }

    @Override
    @Transactional
    public void deleteReview(AuthInfo auth, long recipeId, long reviewId) {
        checkAuth(auth);
        checkReviewBelongsToRecipe(reviewId, recipeId);
        checkReviewOwnership(auth, reviewId);

        // 硬删除点赞记录
        String deleteLikesSql = "DELETE FROM review_likes WHERE review_id = ?";
        jdbcTemplate.update(deleteLikesSql, reviewId);

        // 硬删除评论
        String deleteReviewSql = "DELETE FROM reviews WHERE id = ?";
        jdbcTemplate.update(deleteReviewSql, reviewId);

        updateRecipeRating(recipeId);
    }

    @Override
    @Transactional
    public long likeReview(AuthInfo auth, long reviewId) {
        checkAuth(auth);

        // 获取评论作者ID并验证评论存在
        String checkSql = "SELECT author_id FROM reviews WHERE id = ?";
        Long reviewAuthorId;
        try {
            reviewAuthorId = jdbcTemplate.queryForObject(checkSql, Long.class, reviewId);
            if (reviewAuthorId == null) {
                throw new IllegalArgumentException("Review not found.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review not found.");
        }

        // 不能点赞自己的评论
        if (reviewAuthorId == auth.getAuthorId()) {
            throw new SecurityException("Cannot like your own review.");
        }

        // 插入点赞（忽略重复）
        String sql = "INSERT INTO review_likes (review_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
        jdbcTemplate.update(sql, reviewId, auth.getAuthorId());

        String countSql = "SELECT COUNT(*) FROM review_likes WHERE review_id = ?";
        return jdbcTemplate.queryForObject(countSql, Long.class, reviewId);
    }

    @Override
    @Transactional
    public long unlikeReview(AuthInfo auth, long reviewId) {
        checkAuth(auth);

        // 验证评论存在
        String checkSql = "SELECT 1 FROM reviews WHERE id = ?";
        try {
            jdbcTemplate.queryForObject(checkSql, Integer.class, reviewId);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Review not found.");
        }

        // 删除点赞（如果不存在则无操作）
        String sql = "DELETE FROM review_likes WHERE review_id = ? AND user_id = ?";
        jdbcTemplate.update(sql, reviewId, auth.getAuthorId());

        String countSql = "SELECT COUNT(*) FROM review_likes WHERE review_id = ?";
        return jdbcTemplate.queryForObject(countSql, Long.class, reviewId);
    }

    @Override
    public PageResult<ReviewRecord> listByRecipe(long recipeId, int page, int size, String sort) {
        if (page < 1 || size <= 0) {
            throw new IllegalArgumentException("Invalid page or size.");
        }

        checkRecipeExists(recipeId);

        StringBuilder sqlBuilder = new StringBuilder(
                "SELECT r.*, u.name as author_name FROM reviews r " +
                        "JOIN users u ON r.author_id = u.id " +
                        "WHERE r.recipe_id = ? "
        );
        List<Object> params = new ArrayList<>();
        params.add(recipeId);

        // 获取总数
        String countSql = "SELECT COUNT(*) FROM reviews WHERE recipe_id = ?";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, recipeId);
        if (total == null) total = 0L;

        // 排序
        if ("likes_desc".equals(sort)) {
            sqlBuilder.append("ORDER BY (SELECT COUNT(*) FROM review_likes rl WHERE rl.review_id = r.id) DESC, r.id ASC ");
        } else if ("date_desc".equals(sort)) {
            sqlBuilder.append("ORDER BY r.date_modified DESC, r.id ASC ");
        } else {
            sqlBuilder.append("ORDER BY r.id ASC ");
        }

        // 分页
        sqlBuilder.append("LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);

        List<ReviewRecord> reviews = jdbcTemplate.query(
                sqlBuilder.toString(),
                new ReviewRowMapper(),
                params.toArray()
        );

        // 填充点赞列表
        for (ReviewRecord review : reviews) {
            review.setLikes(getReviewLikes(review.getReviewId()));
        }

        PageResult<ReviewRecord> result = new PageResult<>();
        result.setItems(reviews);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);

        return result;
    }

    /**
     * 获取评论的点赞用户列表
     */
    private long[] getReviewLikes(long reviewId) {
        String sql = "SELECT user_id FROM review_likes WHERE review_id = ? ORDER BY user_id ASC";
        List<Long> likes = jdbcTemplate.queryForList(sql, Long.class, reviewId);
        return likes.stream().mapToLong(Long::longValue).toArray();
    }

    @Override
    @Transactional
    public RecipeRecord refreshRecipeAggregatedRating(long recipeId) {
        checkRecipeExists(recipeId);
        updateRecipeRating(recipeId);

        String sql = "SELECT r.*, u.name as author_name FROM recipes r " +
                "JOIN users u ON r.author_id = u.id " +
                "WHERE r.id = ? AND r.is_deleted = FALSE";
        try {
            RecipeRecord record = jdbcTemplate.queryForObject(sql, new RecipeRowMapper(), recipeId);
            if (record != null) {
                record.setRecipeIngredientParts(getIngredientsByRecipeId(recipeId));
                calculateTotalTime(record);
            }
            return record;
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe not found.");
        }
    }

    /**
     * RowMapper for ReviewRecord
     */
    private static class ReviewRowMapper implements RowMapper<ReviewRecord> {
        @Override
        public ReviewRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            ReviewRecord r = new ReviewRecord();
            r.setReviewId(rs.getLong("id"));
            r.setRecipeId(rs.getLong("recipe_id"));
            r.setAuthorId(rs.getLong("author_id"));
            r.setAuthorName(rs.getString("author_name"));
            r.setRating(rs.getInt("rating"));
            r.setReview(rs.getString("content"));
            r.setDateSubmitted(rs.getTimestamp("date_submitted"));
            r.setDateModified(rs.getTimestamp("date_modified"));
            return r;
        }
    }

    /**
     * RowMapper for RecipeRecord
     */
    private static class RecipeRowMapper implements RowMapper<RecipeRecord> {
        @Override
        public RecipeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            RecipeRecord r = new RecipeRecord();
            r.setRecipeId(rs.getLong("id"));
            r.setAuthorId(rs.getLong("author_id"));
            r.setAuthorName(rs.getString("author_name"));
            r.setName(rs.getString("name"));
            r.setDescription(rs.getString("description"));
            r.setRecipeCategory(rs.getString("category"));
            r.setCookTime(rs.getString("cook_time_iso"));
            r.setPrepTime(rs.getString("prep_time_iso"));
            r.setDatePublished(rs.getTimestamp("date_published"));
            r.setAggregatedRating(rs.getFloat("aggregated_rating"));
            if (rs.wasNull()) {
                r.setAggregatedRating(0);
            }
            r.setReviewCount(rs.getInt("review_count"));
            r.setCalories(rs.getFloat("calories"));
            r.setFatContent(rs.getFloat("fat_content"));
            r.setSaturatedFatContent(rs.getFloat("saturated_fat_content"));
            r.setCholesterolContent(rs.getFloat("cholesterol_content"));
            r.setSodiumContent(rs.getFloat("sodium_content"));
            r.setCarbohydrateContent(rs.getFloat("carbohydrate_content"));
            r.setFiberContent(rs.getFloat("fiber_content"));
            r.setSugarContent(rs.getFloat("sugar_content"));
            r.setProteinContent(rs.getFloat("protein_content"));
            r.setRecipeServings(rs.getInt("servings"));
            r.setRecipeYield(rs.getString("yield"));
            return r;
        }
    }

    private String[] getIngredientsByRecipeId(long recipeId) {
        String sql = "SELECT name FROM recipe_ingredients WHERE recipe_id = ? ORDER BY display_order ASC";
        List<String> ingredients = jdbcTemplate.queryForList(sql, String.class, recipeId);
        return ingredients.toArray(new String[0]);
    }

    private void calculateTotalTime(RecipeRecord record) {
        try {
            Duration cook = Duration.parse(record.getCookTime() != null ? record.getCookTime() : "PT0S");
            Duration prep = Duration.parse(record.getPrepTime() != null ? record.getPrepTime() : "PT0S");
            record.setTotalTime(cook.plus(prep).toString());
        } catch (Exception e) {
            record.setTotalTime(null);
        }
    }
}