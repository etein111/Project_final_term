package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.RecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;

@Service
@Slf4j
public class RecipeServiceImpl implements RecipeService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public String getNameFromID(long id) {
        String sql = "SELECT name FROM recipes WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, String.class, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public RecipeRecord getRecipeById(long recipeId) {
        if (recipeId <= 0) {
            throw new IllegalArgumentException("Recipe ID must be positive.");
        }

        // JOIN users 表以获取 author_name (因为我们遵循了 3NF，去除了 recipes 表里的 author_name 冗余)
        String sql = "SELECT r.*, u.name as author_name " +
                     "FROM recipes r " +
                     "JOIN users u ON r.author_id = u.id " +
                     "WHERE r.id = ? AND r.is_deleted = FALSE";

        try {
            RecipeRecord record = jdbcTemplate.queryForObject(sql, new RecipeRowMapper(), recipeId);
            if (record != null) {
                // 填充配料表 (有序)
                record.setRecipeIngredientParts(getIngredientsByRecipeId(recipeId));
                // 动态计算 TotalTime (DTO要求)
                calculateTotalTime(record);
            }
            return record;
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public PageResult<RecipeRecord> searchRecipes(String keyword, String category, Double minRating, Integer page, Integer size, String sort) {
        if (page < 1 || size <= 0) {
            throw new IllegalArgumentException("Invalid page or size.");
        }

        // 1. 构建动态 SQL
        StringBuilder sqlBuilder = new StringBuilder(
                "SELECT r.*, u.name as author_name FROM recipes r JOIN users u ON r.author_id = u.id WHERE r.is_deleted = FALSE ");
        List<Object> params = new ArrayList<>();

        if (keyword != null && !keyword.isEmpty()) {
            sqlBuilder.append("AND (r.name ILIKE ? OR r.description ILIKE ?) ");
            String likePattern = "%" + keyword + "%";
            params.add(likePattern);
            params.add(likePattern);
        }

        if (category != null && !category.isEmpty()) {
            sqlBuilder.append("AND r.category = ? ");
            params.add(category);
        }

        if (minRating != null) {
            sqlBuilder.append("AND r.aggregated_rating >= ? ");
            params.add(minRating);
        }

        // 2. 获取总条数 (用于分页)
        String countSql = "SELECT COUNT(*) FROM (" + sqlBuilder.toString() + ") AS temp";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) total = 0L;

        // 3. 添加排序
        //加上 r.id 以保证分页的确定性 (Deterministic Sorting)
        if (sort != null) {
            switch (sort) {
                case "rating_desc":
                    sqlBuilder.append("ORDER BY r.aggregated_rating DESC NULLS LAST, r.id ASC ");
                    break;
                case "date_desc":
                    sqlBuilder.append("ORDER BY r.date_published DESC, r.id ASC ");
                    break;
                case "calories_asc":
                    sqlBuilder.append("ORDER BY r.calories ASC NULLS LAST, r.id ASC ");
                    break;
                default:
                    sqlBuilder.append("ORDER BY r.id ASC ");
            }
        } else {
            sqlBuilder.append("ORDER BY r.id ASC ");
        }

        // 4. 添加分页
        sqlBuilder.append("LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);

        // 5. 执行查询
        List<RecipeRecord> records = jdbcTemplate.query(sqlBuilder.toString(), new RecipeRowMapper(), params.toArray());

        // 6. 填充额外信息 (Ingredients & TotalTime)
        for (RecipeRecord record : records) {
            record.setRecipeIngredientParts(getIngredientsByRecipeId(record.getRecipeId()));
            calculateTotalTime(record);
        }

        PageResult<RecipeRecord> result = new PageResult<>();
        result.setItems(records);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);

        return result;
    }

    @Override
    @Transactional
    public long createRecipe(RecipeRecord dto, AuthInfo auth) {
        // 1. 验证用户
        checkAuth(auth);
        
        // 2. 验证输入
        if (dto.getName() == null || dto.getName().isEmpty()) {
            throw new IllegalArgumentException("Recipe name cannot be empty.");
        }

        // 3. 生成新 ID
        Long newId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM recipes", Long.class);

        // 4. 插入 Recipe
        String sql = "INSERT INTO recipes (id, author_id, name, description, category, " +
                "cook_time_iso, cook_time_sec, prep_time_iso, prep_time_sec, date_published, " +
                "aggregated_rating, review_count, " +
                "calories, fat_content, saturated_fat_content, cholesterol_content, sodium_content, " +
                "carbohydrate_content, fiber_content, sugar_content, protein_content, " +
                "servings, yield, is_deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        int cookSec = parseDuration(dto.getCookTime());
        int prepSec = parseDuration(dto.getPrepTime());

        jdbcTemplate.update(sql,
                newId,
                auth.getAuthorId(),
                dto.getName(),
                dto.getDescription(),
                dto.getRecipeCategory(),
                dto.getCookTime(), cookSec,
                dto.getPrepTime(), prepSec,
                new Timestamp(System.currentTimeMillis()), // Published now
                null, 0, // Initial rating and review count
                dto.getCalories(), dto.getFatContent(), dto.getSaturatedFatContent(), dto.getCholesterolContent(), dto.getSodiumContent(),
                dto.getCarbohydrateContent(), dto.getFiberContent(), dto.getSugarContent(), dto.getProteinContent(),
                dto.getRecipeServings(), dto.getRecipeYield(),
                false
        );

        // 5. 插入 Ingredients (维护 display_order)
        if (dto.getRecipeIngredientParts() != null) {
            String ingSql = "INSERT INTO recipe_ingredients (recipe_id, name, display_order) VALUES (?, ?, ?)";
            String[] parts = dto.getRecipeIngredientParts();
            for (int i = 0; i < parts.length; i++) {
                jdbcTemplate.update(ingSql, newId, parts[i], i);
            }
        }

        return newId;
    }

    @Override
    @Transactional
    public void deleteRecipe(long recipeId, AuthInfo auth) {
        checkAuth(auth);

        // 检查 Recipe 是否存在且属于该用户
        String checkSql = "SELECT author_id FROM recipes WHERE id = ? AND is_deleted = FALSE";
        try {
            Long authorId = jdbcTemplate.queryForObject(checkSql, Long.class, recipeId);
            if (authorId == null || authorId != auth.getAuthorId()) {
                throw new SecurityException("You are not the author of this recipe.");
            }
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("Recipe not found.");
        }

        // 执行软删除
        jdbcTemplate.update("UPDATE recipes SET is_deleted = TRUE WHERE id = ?", recipeId);
    }

    @Override
    @Transactional
    public void updateTimes(AuthInfo auth, long recipeId, String cookTimeIso, String prepTimeIso) {
        checkAuth(auth);

        // 检查所有权
        String checkSql = "SELECT author_id FROM recipes WHERE id = ? AND is_deleted = FALSE";
        try {
            Long authorId = jdbcTemplate.queryForObject(checkSql, Long.class, recipeId);
            if (authorId == null || authorId != auth.getAuthorId()) {
                throw new SecurityException("Access denied.");
            }
        } catch (EmptyResultDataAccessException e) {
            // 这里根据文档，如果用户没权限或已删除，是 SecurityException，如果没找到可能是 IllegalArgument
            // 但根据逻辑，所有者检查优先
            throw new IllegalArgumentException("Recipe not found.");
        }

        // 验证并构建 Update
        StringBuilder updateSql = new StringBuilder("UPDATE recipes SET ");
        List<Object> params = new ArrayList<>();
        boolean needsUpdate = false;

        if (cookTimeIso != null) {
            int sec = parseDurationStrict(cookTimeIso); // 必须是有效的 ISO
            updateSql.append("cook_time_iso = ?, cook_time_sec = ?, ");
            params.add(cookTimeIso);
            params.add(sec);
            needsUpdate = true;
        }

        if (prepTimeIso != null) {
            int sec = parseDurationStrict(prepTimeIso);
            updateSql.append("prep_time_iso = ?, prep_time_sec = ?, ");
            params.add(prepTimeIso);
            params.add(sec);
            needsUpdate = true;
        }

        if (needsUpdate) {
            // 去掉最后的逗号和空格
            updateSql.setLength(updateSql.length() - 2);
            updateSql.append(" WHERE id = ?");
            params.add(recipeId);
            jdbcTemplate.update(updateSql.toString(), params.toArray());
        }
    }

    @Override
    public Map<String, Object> getClosestCaloriePair() {
        // 全SQL计算，利用自连接 (Self-Join)
        // r1.id < r2.id 避免重复和自身比较
        String sql = "SELECT r1.id as id1, r2.id as id2, r1.calories as cal1, r2.calories as cal2, " +
                     "ABS(r1.calories - r2.calories) as diff " +
                     "FROM recipes r1 " +
                     "JOIN recipes r2 ON r1.id < r2.id " +
                     "WHERE r1.is_deleted = FALSE AND r2.is_deleted = FALSE " +
                     "AND r1.calories IS NOT NULL AND r2.calories IS NOT NULL " +
                     "ORDER BY diff ASC, r1.id ASC, r2.id ASC " +
                     "LIMIT 1";

        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("RecipeA", rs.getLong("id1"));
                map.put("RecipeB", rs.getLong("id2"));
                map.put("CaloriesA", rs.getDouble("cal1"));
                map.put("CaloriesB", rs.getDouble("cal2"));
                map.put("Difference", rs.getDouble("diff"));
                return map;
            });
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public List<Map<String, Object>> getTop3MostComplexRecipesByIngredients() {
        // 分组统计
        String sql = "SELECT r.id, r.name, COUNT(ri.id) as cnt " +
                     "FROM recipes r " +
                     "JOIN recipe_ingredients ri ON r.id = ri.recipe_id " +
                     "WHERE r.is_deleted = FALSE " +
                     "GROUP BY r.id, r.name " +
                     "ORDER BY cnt DESC, r.id ASC " +
                     "LIMIT 3";

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("RecipeId", rs.getLong("id"));
            map.put("Name", rs.getString("name"));
            map.put("IngredientCount", rs.getInt("cnt"));
            return map;
        });
    }

    // ==========================================
    // 辅助方法 (Helpers)
    // ==========================================

    /**
     * 验证用户是否合法 (存在且未删除)
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

    private String[] getIngredientsByRecipeId(long recipeId) {
        // ORDER BY display_order 保证数组顺序
        String sql = "SELECT name FROM recipe_ingredients WHERE recipe_id = ? ORDER BY display_order ASC";
        List<String> ingredients = jdbcTemplate.queryForList(sql, String.class, recipeId);
        return ingredients.toArray(new String[0]);
    }

    private int parseDuration(String iso) {
        if (iso == null) return 0;
        try {
            return (int) Duration.parse(iso).getSeconds();
        } catch (Exception e) {
            return 0;
        }
    }

    private int parseDurationStrict(String iso) {
        try {
            long seconds = Duration.parse(iso).getSeconds();
            if (seconds < 0) throw new IllegalArgumentException();
            return (int) seconds;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ISO duration: " + iso);
        }
    }

    private void calculateTotalTime(RecipeRecord record) {
        try {
            Duration cook = Duration.parse(record.getCookTime() != null ? record.getCookTime() : "PT0S");
            Duration prep = Duration.parse(record.getPrepTime() != null ? record.getPrepTime() : "PT0S");
            record.setTotalTime(cook.plus(prep).toString());
        } catch (Exception e) {
            // 如果解析失败，不设置 totalTime，防止前端炸裂
            record.setTotalTime(null);
        }
    }

    // RowMapper 用于将 ResultSet 映射为 RecipeRecord
    private static class RecipeRowMapper implements RowMapper<RecipeRecord> {
        @Override
        public RecipeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            RecipeRecord r = new RecipeRecord();
            r.setRecipeId(rs.getLong("id"));
            r.setAuthorId(rs.getLong("author_id"));
            r.setAuthorName(rs.getString("author_name")); // 从 JOIN 的结果中获取
            r.setName(rs.getString("name"));
            r.setDescription(rs.getString("description"));
            r.setRecipeCategory(rs.getString("category"));
            r.setCookTime(rs.getString("cook_time_iso"));
            r.setPrepTime(rs.getString("prep_time_iso"));
            r.setDatePublished(rs.getTimestamp("date_published"));
            r.setAggregatedRating(rs.getFloat("aggregated_rating"));
            // 注意：float 转 Float，如果 SQL 是 NULL，rs.getFloat 返回 0.0，需要判断 wasNull
            if (rs.wasNull()) r.setAggregatedRating(0); // 
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
}