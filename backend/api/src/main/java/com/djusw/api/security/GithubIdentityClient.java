package com.djusw.api.security;

import java.util.Set;

public interface GithubIdentityClient {
    GithubIdentity authenticate(String code, String verifier);

    record GithubIdentity(long id, String login, Set<Long> repositoryIds) {
        public GithubIdentity { repositoryIds = Set.copyOf(repositoryIds); }
    }
}
