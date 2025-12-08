
-- Clean up existing tables (ORDER MATTERS due to Foreign Keys)
DROP TABLE IF EXISTS review_likes CASCADE;
DROP TABLE IF EXISTS user_follows CASCADE;
DROP TABLE IF EXISTS recipe_ingredients CASCADE;
DROP TABLE IF EXISTS reviews CASCADE;
DROP TABLE IF EXISTS recipes CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- ==========================================
-- 1. Users Table
-- Source: users.csv
-- ==========================================
CREATE TABLE users (
    id BIGINT PRIMARY KEY,                 -- Imported AuthorId
    name VARCHAR(255) UNIQUE NOT NULL,     -- Imported AuthorName (Must be unique per Req)
    password VARCHAR(255),                 -- To be hashed in Service layer
    gender VARCHAR(50),                    -- 'Male', 'Female', 'Unknown'
    age INT,

    -- Permission & Status Management
    role VARCHAR(20) DEFAULT 'USER',       -- 'USER' or 'ADMIN'
    is_deleted BOOLEAN DEFAULT FALSE       -- Soft Delete Flag
);

-- ==========================================
-- 2. Recipes Table
-- Source: recipes.csv
-- Note: 'author_name' removed (Normalization). Use JOIN users.
-- ==========================================
CREATE TABLE recipes (
    id BIGINT PRIMARY KEY,                 -- Imported RecipeId
    author_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,            -- Recipe Title
    description TEXT,
    category VARCHAR(100),                 -- e.g. 'Lunch/Snacks'

    -- Time Logic (ISO for display, Seconds for calculation/sorting)
    cook_time_iso VARCHAR(50),             -- e.g. 'PT3H'
    cook_time_sec INT DEFAULT 0,           -- Parsed Seconds
    prep_time_iso VARCHAR(50),
    prep_time_sec INT DEFAULT 0,
    date_published TIMESTAMP,

    -- Statistics (Denormalized for Performance requirement)
    aggregated_rating FLOAT,               -- Updated by ReviewService
    review_count INT DEFAULT 0,            -- Updated by ReviewService

    -- Nutrition Information
    calories FLOAT,
    fat_content FLOAT,
    saturated_fat_content FLOAT,
    cholesterol_content FLOAT,
    sodium_content FLOAT,
    carbohydrate_content FLOAT,
    fiber_content FLOAT,
    sugar_content FLOAT,
    protein_content FLOAT,

    -- Servings
    servings INT,
    yield VARCHAR(100),

    -- Status
    is_deleted BOOLEAN DEFAULT FALSE
);

-- ==========================================
-- 3. Recipe Ingredients Table
-- Source: recipes.csv (RecipeIngredientParts column)
-- Rationale: 1:N Relationship, preserving order.
-- ==========================================
CREATE TABLE recipe_ingredients (
    id SERIAL PRIMARY KEY,                 -- Auto-increment for internal ID
    recipe_id BIGINT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    display_order INT NOT NULL             -- CRITICAL: To maintain array order [0, 1, 2...]
);

-- ==========================================
-- 4. Reviews Table
-- Source: reviews.csv
-- ==========================================
CREATE TABLE reviews (
    id BIGINT PRIMARY KEY,                 -- Imported ReviewId
    recipe_id BIGINT NOT NULL REFERENCES recipes(id),
    author_id BIGINT NOT NULL REFERENCES users(id),
    rating INT CHECK (rating >= 1 AND rating <= 5), -- Constraint 1-5
    content TEXT,
    date_submitted TIMESTAMP,
    date_modified TIMESTAMP
);

-- ==========================================
-- 5. Review Likes Table
-- Source: reviews.csv (Likes column)
-- Rationale: M:N Relationship
-- ==========================================
CREATE TABLE review_likes (
    user_id BIGINT NOT NULL REFERENCES users(id),
    review_id BIGINT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- Optional: for sorting likes
    PRIMARY KEY (user_id, review_id) -- Composite PK prevents duplicate likes
);

-- ==========================================
-- 6. User Follows Table
-- Source: users.csv (FollowerUsers/FollowingUsers columns)
-- Rationale: M:N Relationship (Self-Referencing)
-- ==========================================
CREATE TABLE user_follows (
    follower_id BIGINT NOT NULL REFERENCES users(id),
    followee_id BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (follower_id, followee_id), -- Prevent duplicate follows
    CHECK (follower_id <> followee_id)      -- Prevent self-following
);

-- ==========================================
-- Performance Tuning: Indexes
-- Based on Query Patterns in Service Interfaces
-- ==========================================

-- 1. Optimize searchRecipes
-- Helps: WHERE category = ? ORDER BY aggregated_rating DESC
CREATE INDEX idx_recipes_cat_rating ON recipes(category, aggregated_rating DESC);
-- Helps: WHERE category = ? ORDER BY date_published DESC
CREATE INDEX idx_recipes_cat_date ON recipes(category, date_published DESC);
-- Helps: WHERE category = ? ORDER BY calories ASC
CREATE INDEX idx_recipes_cat_cal ON recipes(category, calories ASC);
-- Helps: Fuzzy search on title (Simple B-Tree for 'ABC%')
-- Note: For '%ABC%', you strictly need pg_trgm, but standard index helps prefix scan.
CREATE INDEX idx_recipes_title ON recipes(name);

-- 2. Optimize Foreign Keys (Crucial for JOINs)
CREATE INDEX idx_recipes_author ON recipes(author_id);
CREATE INDEX idx_reviews_recipe ON reviews(recipe_id);
CREATE INDEX idx_reviews_author ON reviews(author_id);

-- 3. Optimize Ingredient Retrieval
-- CRITICAL: Helps getRecipeById retrieve ingredients in correct order
CREATE INDEX idx_ingredients_lookup ON recipe_ingredients(recipe_id, display_order);

-- 4. Optimize Follow Checks
-- Helps: getUserWithHighestFollowRatio AND feed
CREATE INDEX idx_follows_follower ON user_follows(follower_id);
CREATE INDEX idx_follows_followee ON user_follows(followee_id);

-- 5. Optimize Review Retrieval
CREATE INDEX idx_likes_review ON review_likes(review_id);
