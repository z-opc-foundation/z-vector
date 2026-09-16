package com.zifang.z.vector.api;

/**
 * 向量库版本兼容异常 — 操作与集合配置不兼容时抛出。
 */
public class VectorException extends RuntimeException {

    public VectorException(String message) {
        super(message);
    }

    public VectorException(String message, Throwable cause) {
        super(message, cause);
    }
}