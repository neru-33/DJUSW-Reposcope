package com.djusw.api.security;

import java.io.Serializable;
import java.util.Objects;

/** Identity placed in the security context by a successful login. */
public record CurrentUser(long id, Role role) implements Serializable {
    public CurrentUser {
        Objects.requireNonNull(role, "role");
    }

    public enum Role { SUPER_ADMIN, ADMIN, PROFESSOR, STUDENT, GUEST }
}
