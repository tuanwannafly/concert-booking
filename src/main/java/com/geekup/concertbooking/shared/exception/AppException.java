package com.geekup.concertbooking.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AppException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.httpStatus = errorCode.getHttpStatus();
        this.errorCode = errorCode.name();
    }

    public AppException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.httpStatus = errorCode.getHttpStatus();
        this.errorCode = errorCode.name();
    }
}
