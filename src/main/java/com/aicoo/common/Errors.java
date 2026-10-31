package com.aicoo.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import java.util.Map;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class Errors {
    public static Map<String,Object> body(String code,String message,Object requestId){return Map.of("error",Map.of("code",code,"message",message,"requestId",requestId==null?"unknown":requestId));}
    @ExceptionHandler(ApiException.class) ResponseEntity<?> api(ApiException e,HttpServletRequest request){return ResponseEntity.status(e.status).body(body(e.code,e.getMessage(),request.getAttribute("requestId")));}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class,MissingServletRequestParameterException.class}) ResponseEntity<?> validation(Exception e,HttpServletRequest request){return ResponseEntity.unprocessableEntity().body(body("VALIDATION_ERROR","입력 형식과 필수 항목을 확인해 주세요.",request.getAttribute("requestId")));}
    @ExceptionHandler(NoResourceFoundException.class) ResponseEntity<?> missing(Exception e,HttpServletRequest request){return ResponseEntity.status(404).body(body("NOT_FOUND","요청한 경로가 없어요.",request.getAttribute("requestId")));}
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception e,HttpServletRequest request){return ResponseEntity.status(405).body(body("METHOD_NOT_ALLOWED","지원하지 않는 요청 방식이에요.",request.getAttribute("requestId")));}
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e,HttpServletRequest request){return ResponseEntity.status(409).body(body("DATA_CONFLICT","중복되거나 유효하지 않은 참조입니다.",request.getAttribute("requestId")));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e,HttpServletRequest request){LoggerFactory.getLogger(Errors.class).error("request_failed requestId={} exception={}",request.getAttribute("requestId"),e.getClass().getSimpleName());return ResponseEntity.status(500).body(body("INTERNAL_ERROR","요청을 처리하지 못했어요.",request.getAttribute("requestId")));}
}
