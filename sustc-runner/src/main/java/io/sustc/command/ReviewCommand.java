package io.sustc.command;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.RecipeRecord;
import io.sustc.service.ReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@Slf4j
@ShellComponent
public class ReviewCommand {

    @Autowired
    private ReviewService reviewService;

    @ShellMethod(key = "review add", value = "Add a review to a recipe")
    public String reviewAdd(
            @ShellOption long userId,
            @ShellOption String password,
            @ShellOption long recipeId,
            @ShellOption int rating,
            @ShellOption String reviewText) {
        try {
            AuthInfo auth = AuthInfo.builder()
                    .authorId(userId)
                    .password(password)
                    .build();
            long reviewId = reviewService.addReview(auth, recipeId, rating, reviewText);
            return "Review created successfully! ID: " + reviewId;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review list", value = "List reviews for a recipe")
    public String reviewList(
            @ShellOption long recipeId,
            @ShellOption(defaultValue = "1") int page,
            @ShellOption(defaultValue = "10") int size,
            @ShellOption(defaultValue = "date_desc") String sort) {
        try {
            var result = reviewService.listByRecipe(recipeId, page, size, sort);
            return String.format("Page %d/%d, Total: %d%n%s",
                    result.getPage(),
                    (result.getTotal() + result.getSize() - 1) / result.getSize(),
                    result.getTotal(),
                    result.getItems().toString());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review edit", value = "Edit a review")
    public String reviewEdit(
            @ShellOption long userId,
            @ShellOption String password,
            @ShellOption long recipeId,
            @ShellOption long reviewId,
            @ShellOption int rating,
            @ShellOption String reviewText) {
        try {
            AuthInfo auth = AuthInfo.builder()
                    .authorId(userId)
                    .password(password)
                    .build();
            reviewService.editReview(auth, recipeId, reviewId, rating, reviewText);
            return "Review edited successfully!";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review delete", value = "Delete a review")
    public String reviewDelete(
            @ShellOption long userId,
            @ShellOption String password,
            @ShellOption long recipeId,
            @ShellOption long reviewId) {
        try {
            AuthInfo auth = AuthInfo.builder()
                    .authorId(userId)
                    .password(password)
                    .build();
            reviewService.deleteReview(auth, recipeId, reviewId);
            return "Review deleted successfully!";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review like", value = "Like a review")
    public String reviewLike(
            @ShellOption long userId,
            @ShellOption String password,
            @ShellOption long reviewId) {
        try {
            AuthInfo auth = AuthInfo.builder()
                    .authorId(userId)
                    .password(password)
                    .build();
            long likes = reviewService.likeReview(auth, reviewId);
            return "Review liked! Total likes: " + likes;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review unlike", value = "Unlike a review")
    public String reviewUnlike(
            @ShellOption long userId,
            @ShellOption String password,
            @ShellOption long reviewId) {
        try {
            AuthInfo auth = AuthInfo.builder()
                    .authorId(userId)
                    .password(password)
                    .build();
            long likes = reviewService.unlikeReview(auth, reviewId);
            return "Review unliked! Total likes: " + likes;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @ShellMethod(key = "review refresh-rating", value = "Refresh recipe rating")
    public String reviewRefreshRating(@ShellOption long recipeId) {
        try {
            RecipeRecord recipe = reviewService.refreshRecipeAggregatedRating(recipeId);
            return String.format("Rating updated! Recipe ID: %d, Rating: %.2f, Reviews: %d",
                    recipeId,
                    recipe.getAggregatedRating(),
                    recipe.getReviewCount());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}