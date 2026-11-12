package com.ehe.iotagent.example;

import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.example.internal.DefaultGreeting;

/**
 * 拷贝模板：模块 = 包根的入口类 + {@code internal} 子包实现。
 * 入口负责校验与委托；外部只准 import 包根类，internal 不可触碰（ArchitectureTest 强制）。
 * 新模块照此起形，本模块可随时删除。
 */
public final class GreetingFacade {

    private final DefaultGreeting impl = new DefaultGreeting();

    public String greet(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("EMPTY_NAME", "称呼不能为空");
        }
        return impl.format(name.strip());
    }
}
