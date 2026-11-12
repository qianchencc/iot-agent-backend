package com.ehe.iotagent.example.internal;

/**
 * internal 实现：public 仅为跨包可编译（Java 无包级友元），
 * 「仅限本模块访问」由 ArchitectureTest 的入口边界规则强制。
 */
public final class DefaultGreeting {

    public String format(String name) {
        return "你好，" + name;
    }
}
