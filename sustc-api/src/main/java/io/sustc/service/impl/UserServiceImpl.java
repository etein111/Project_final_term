package io.sustc.service.impl;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PageResult;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserRecord;
import io.sustc.dto.FeedItem;
import io.sustc.service.UserService;
import org.springframework.stereotype.Service;
import javax.annotation.Nullable;
import java.util.Map;

@Service 
public class UserServiceImpl implements UserService {

    @Override
    public long register(RegisterUserReq req) {
        return -1; // 占位符
    }

    @Override
    public long login(AuthInfo auth) {
        return -1;
    }

    @Override
    public boolean deleteAccount(AuthInfo auth, long userId) {
        return false;
    }

    @Override
    public boolean follow(AuthInfo auth, long followeeId) {
        return false;
    }

    @Override
    public UserRecord getById(long userId) {
        return null;
    }

    @Override
    public void updateProfile(AuthInfo auth, String gender, Integer age) {
        // 空实现
    }

    @Override
    public PageResult<FeedItem> feed(AuthInfo auth, int page, int size, @Nullable String category) {
        return null;
    }

    @Override
    public Map<String, Object> getUserWithHighestFollowRatio() {
        return null;
    }
}