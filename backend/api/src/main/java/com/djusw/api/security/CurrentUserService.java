package com.djusw.api.security;

import com.djusw.api.error.ApiException;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserService {
    public CurrentUser requireCurrentUser() {
        return currentUser().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다."));
    }

    public boolean isLoggedIn() {
        return currentUser().isPresent();
    }

    private Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public CurrentUser requireCompletedProfile() {
        CurrentUser user = requireCurrentUser();
        if (user.role() == CurrentUser.Role.GUEST) {
            throw new ApiException(HttpStatus.FORBIDDEN, "profile_required", "가입을 완료해 주세요.");
        }
        return user;
    }

    public CurrentUser requireRole(CurrentUser.Role... roles) {
        CurrentUser user = requireCompletedProfile();
        if (!Arrays.asList(roles).contains(user.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "접근 권한이 없습니다.");
        }
        return user;
    }
}
