package com.zzf.codeagent.api;

import com.zzf.codeagent.model.CustomException;
import com.zzf.codeagent.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(new ErrorResponse(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknownException(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = e.getClass().getSimpleName();
        } else {
            msg = e.getClass().getSimpleName() + ": " + msg;
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "application/json;charset=UTF-8")
                .body(new ErrorResponse("INTERNAL_ERROR", msg));
    }
}
