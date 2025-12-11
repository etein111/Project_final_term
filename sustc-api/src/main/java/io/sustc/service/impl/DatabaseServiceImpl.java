package io.sustc.service.impl;

import io.sustc.dto.ReviewRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
/**
 * It's important to mark your implementation class with {@link Service} annotation.
 * As long as the class is annotated and implements the corresponding interface, you can place it under any package.
 */
@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    /**
     * Getting a {@link DataSource} instance from the framework, whose connections are managed by HikariCP.
     * <p>
     * Marking a field with {@link Autowired} annotation enables our framework to automatically
     * provide you a well-configured instance of {@link DataSource}.
     * Learn more: <a href="https://www.baeldung.com/spring-dependency-injection">Dependency Injection</a>
     */
    @Autowired
    private DataSource dataSource;

    @Override
    public List<Integer> getGroupMembers() {
        //TODO: replace this with your own student IDs in your group
        return Arrays.asList(12210732, 12210924);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void importData(
            List<ReviewRecord> reviewRecords,
            List<UserRecord> userRecords,
            List<RecipeRecord> recipeRecords) {

        // ddl to create tables.

        drop();

        createTables();

        if (userRecords == null || userRecords.isEmpty()) {
            log.info("No data to import.");
            return;
        }

        // 2. Insert Users
        String userSql = "INSERT INTO users (id, name, password, gender, age, role, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(userSql, userRecords, 1000, (ps, user) -> {
            ps.setLong(1, user.getAuthorId());
            ps.setString(2, user.getAuthorName());
            ps.setString(3, user.getPassword());
            ps.setString(4, user.getGender());
            ps.setInt(5, user.getAge());
            ps.setString(6, "USER"); // 默认角色
            ps.setBoolean(7, false); // 默认未删除
        });
        log.info("Users imported: {}", userRecords.size());

        // 3. 处理 User Follows (多对多)
        String followSql = "INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
        List<long[]> followPairs = new ArrayList<>();

        for (UserRecord user : userRecords) {
            long[] following = user.getFollowingUsers();
            if (following != null) {
                for (long targetId : following) {
                    followPairs.add(new long[]{user.getAuthorId(), targetId});
                }
            }
        }

        jdbcTemplate.batchUpdate(followSql, followPairs, 1000, (ps, pair) -> {
            ps.setLong(1, pair[0]);
            ps.setLong(2, pair[1]);
        });
        log.info("User Follows imported.");

        // 4. 批量插入 Recipes
        String recipeSql = "INSERT INTO recipes (id, author_id, name, description, category, " +
                "cook_time_iso, cook_time_sec, prep_time_iso, prep_time_sec, date_published, " +
                "aggregated_rating, review_count, " +
                "calories, fat_content, saturated_fat_content, cholesterol_content, sodium_content, " +
                "carbohydrate_content, fiber_content, sugar_content, protein_content, " +
                "servings, yield, is_deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(recipeSql, recipeRecords, 1000, (ps, r) -> {
            ps.setLong(1, r.getRecipeId());
            ps.setLong(2, r.getAuthorId());
            ps.setString(3, r.getName());
            ps.setString(4, r.getDescription());
            ps.setString(5, r.getRecipeCategory());

            // 时间转换逻辑
            ps.setString(6, r.getCookTime());
            ps.setInt(7, parseIsoDuration(r.getCookTime()));
            ps.setString(8, r.getPrepTime());
            ps.setInt(9, parseIsoDuration(r.getPrepTime()));

            ps.setTimestamp(10, r.getDatePublished());
            ps.setFloat(11, r.getAggregatedRating());
            ps.setInt(12, r.getReviewCount());

            // 营养成分
            ps.setFloat(13, r.getCalories());
            ps.setFloat(14, r.getFatContent());
            ps.setFloat(15, r.getSaturatedFatContent());
            ps.setFloat(16, r.getCholesterolContent());
            ps.setFloat(17, r.getSodiumContent());
            ps.setFloat(18, r.getCarbohydrateContent());
            ps.setFloat(19, r.getFiberContent());
            ps.setFloat(20, r.getSugarContent());
            ps.setFloat(21, r.getProteinContent());

            ps.setInt(22, r.getRecipeServings());
            ps.setString(23, r.getRecipeYield());
            ps.setBoolean(24, false);
        });
        log.info("Recipes imported: {}", recipeRecords.size());

        // 5. 处理 Recipe Ingredients (1对多，带顺序)
        String ingredientSql = "INSERT INTO recipe_ingredients (recipe_id, name, display_order) VALUES (?, ?, ?)";
        List<Object[]> ingredientArgs = new ArrayList<>();

        for (RecipeRecord r : recipeRecords) {
            String[] ingredients = r.getRecipeIngredientParts();
            if (ingredients != null) {
                for (int i = 0; i < ingredients.length; i++) {
                    // Object[] 对应 SQL 参数: recipe_id, name, display_order
                    ingredientArgs.add(new Object[]{r.getRecipeId(), ingredients[i], i});
                }
            }
        }

        jdbcTemplate.batchUpdate(ingredientSql, ingredientArgs, 1000, (ps, args) -> {
            ps.setLong(1, (Long) args[0]);
            ps.setString(2, (String) args[1]);
            ps.setInt(3, (Integer) args[2]);
        });
        log.info("Recipe Ingredients imported.");

        // 6. 批量插入 Reviews
        String reviewSql = "INSERT INTO reviews (id, recipe_id, author_id, rating, content, date_submitted, date_modified) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(reviewSql, reviewRecords, 1000, (ps, rev) -> {
            ps.setLong(1, rev.getReviewId());
            ps.setLong(2, rev.getRecipeId());
            ps.setLong(3, rev.getAuthorId());
            ps.setFloat(4, rev.getRating());
            ps.setString(5, rev.getReview());
            ps.setTimestamp(6, rev.getDateSubmitted());
            ps.setTimestamp(7, rev.getDateModified());
        });
        log.info("Reviews imported: {}", reviewRecords.size());

        // 7. 处理 Review Likes (多对多)
        String likeSql = "INSERT INTO review_likes (review_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING";
        List<long[]> likePairs = new ArrayList<>(); // review_id, user_id

        for (ReviewRecord rev : reviewRecords) {
            long[] likes = rev.getLikes(); // 这个字段在 DTO 里通常叫 getLikes
            if (likes != null) {
                for (long userId : likes) {
                    likePairs.add(new long[]{rev.getReviewId(), userId});
                }
            }
        }

        jdbcTemplate.batchUpdate(likeSql, likePairs, 5000, (ps, pair) -> {
            ps.setLong(1, pair[0]);
            ps.setLong(2, pair[1]);
        });
        log.info("Review Likes imported.");
    }

    // 解析 ISO 8601 时间 (e.g., "PT1H30M" -> 5400)
    private int parseIsoDuration(String isoDuration) {
        if (isoDuration == null || isoDuration.isEmpty()) {
            return 0;
        }
        try {
            return (int) Duration.parse(isoDuration).getSeconds();
        } catch (Exception e) {
            log.warn("Failed to parse duration: {}", isoDuration);
            return 0;
        }
    }


    private void createTables() {
        // 这里对应 schema.sql 的内容，去掉了冗余字段，增加了必要的约束和索引
        String[] sqls = {
                "CREATE TABLE IF NOT EXISTS users (" +
                        "id BIGINT PRIMARY KEY, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "password VARCHAR(255), " +
                        "gender VARCHAR(50), " +
                        "age INT, " +
                        "role VARCHAR(20) DEFAULT 'USER', " +
                        "is_deleted BOOLEAN DEFAULT FALSE)",

                "CREATE TABLE IF NOT EXISTS recipes (" +
                        "id BIGINT PRIMARY KEY, " +
                        "author_id BIGINT NOT NULL REFERENCES users(id), " +
                        "name VARCHAR(255) NOT NULL, " +
                        "description TEXT, " +
                        "category VARCHAR(100), " +
                        "cook_time_iso VARCHAR(50), " +
                        "cook_time_sec INT DEFAULT 0, " +
                        "prep_time_iso VARCHAR(50), " +
                        "prep_time_sec INT DEFAULT 0, " +
                        "date_published TIMESTAMP, " +
                        "aggregated_rating FLOAT, " +
                        "review_count INT DEFAULT 0, " +
                        "calories FLOAT, " +
                        "fat_content FLOAT, " +
                        "saturated_fat_content FLOAT, " +
                        "cholesterol_content FLOAT, " +
                        "sodium_content FLOAT, " +
                        "carbohydrate_content FLOAT, " +
                        "fiber_content FLOAT, " +
                        "sugar_content FLOAT, " +
                        "protein_content FLOAT, " +
                        "servings INT, " +
                        "yield VARCHAR(100), " +
                        "is_deleted BOOLEAN DEFAULT FALSE)",

                "CREATE TABLE IF NOT EXISTS recipe_ingredients (" +
                        "id SERIAL PRIMARY KEY, " +
                        "recipe_id BIGINT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "display_order INT NOT NULL)",

                "CREATE TABLE IF NOT EXISTS reviews (" +
                        "id BIGINT PRIMARY KEY, " +
                        "recipe_id BIGINT NOT NULL REFERENCES recipes(id), " +
                        "author_id BIGINT NOT NULL REFERENCES users(id), " +
                        "rating INT, " +
                        "content TEXT, " +
                        "date_submitted TIMESTAMP, " +
                        "date_modified TIMESTAMP)",

                "CREATE TABLE IF NOT EXISTS review_likes (" +
                        "user_id BIGINT NOT NULL REFERENCES users(id), " +
                        "review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE, " +
                        "PRIMARY KEY (user_id, review_id))",

                "CREATE TABLE IF NOT EXISTS user_follows (" +
                        "follower_id BIGINT NOT NULL REFERENCES users(id), " +
                        "followee_id BIGINT NOT NULL REFERENCES users(id), " +
                        "PRIMARY KEY (follower_id, followee_id))",

                // 必须创建索引，否则 Benchmark 会超时
                "CREATE INDEX IF NOT EXISTS idx_recipes_cat_rating ON recipes(category, aggregated_rating DESC)",
                "CREATE INDEX IF NOT EXISTS idx_recipes_cat_cal ON recipes(category, calories ASC)",
                "CREATE INDEX IF NOT EXISTS idx_ingredients_lookup ON recipe_ingredients(recipe_id, display_order)"
        };

        for (String sql : sqls) {
            jdbcTemplate.execute(sql);
        }
    }




    /*
     * The following code is just a quick example of using jdbc datasource.
     * Practically, the code interacts with database is usually written in a DAO layer.
     *
     * Reference: [Data Access Object pattern](https://www.baeldung.com/java-dao-pattern)
     */

    @Override
    public void drop() {
        // You can use the default drop script provided by us in most cases,
        // but if it doesn't work properly, you may need to modify it.
        // This method will delete all the tables in the public schema.

        String sql = "DO $$\n" +
                "DECLARE\n" +
                "    tables CURSOR FOR\n" +
                "        SELECT tablename\n" +
                "        FROM pg_tables\n" +
                "        WHERE schemaname = 'public';\n" +
                "BEGIN\n" +
                "    FOR t IN tables\n" +
                "    LOOP\n" +
                "        EXECUTE 'DROP TABLE IF EXISTS ' || QUOTE_IDENT(t.tablename) || ' CASCADE;';\n" +
                "    END LOOP;\n" +
                "END $$;\n";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer sum(int a, int b) {
        String sql = "SELECT ?+?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, a);
            stmt.setInt(2, b);
            log.info("SQL: {}", stmt);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}