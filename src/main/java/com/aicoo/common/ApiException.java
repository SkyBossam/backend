package com.aicoo.common;

public class ApiException extends RuntimeException {
    public final int status; public final String code;
    public ApiException(int status,String code,String message){super(message);this.status=status;this.code=code;}
    public static ApiException notFound(String code){return new ApiException(404,code,"요청한 리소스를 찾을 수 없어요.");}
    public static ApiException forbidden(String code){return new ApiException(403,code,"이 작업에 필요한 권한이 없어요.");}
    public static ApiException invalid(String code){return new ApiException(422,code,"요청 값 또는 상태를 확인해 주세요.");}
    public static ApiException conflict(String code){return new ApiException(409,code,"현재 상태에서 요청을 처리할 수 없어요.");}
    public static ApiException unauthorized(){return new ApiException(401,"AUTHENTICATION_REQUIRED","로그인이 필요해요.");}
}
