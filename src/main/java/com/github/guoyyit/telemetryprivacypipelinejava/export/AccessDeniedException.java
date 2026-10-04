package com.github.guoyyit.telemetryprivacypipelinejava.export;

/**
 * 越权访问异常：非授权调用方请求未脱敏原始值时抛出，且必须留痕。
 */
public class AccessDeniedException extends RuntimeException {

    private final Caller caller;
    private final String resource;

    public AccessDeniedException(Caller caller, String resource, String message) {
        super(message);
        this.caller = caller;
        this.resource = resource;
    }

    public Caller caller() {
        return caller;
    }

    public String resource() {
        return resource;
    }
}
