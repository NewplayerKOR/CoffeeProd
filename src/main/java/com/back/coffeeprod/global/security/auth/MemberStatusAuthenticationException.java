package com.back.coffeeprod.global.security.auth;

import com.back.coffeeprod.global.exception.ErrorCode;
import org.springframework.security.core.AuthenticationException;

/** Keeps the member-state HTTP contract when authentication fails before MVC. */
public class MemberStatusAuthenticationException extends AuthenticationException {
    private final ErrorCode errorCode;

    public MemberStatusAuthenticationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
