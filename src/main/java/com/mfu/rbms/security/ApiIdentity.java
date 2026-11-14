package com.mfu.rbms.security;

public record ApiIdentity(long id, String username, String displayName, String role) {
}

