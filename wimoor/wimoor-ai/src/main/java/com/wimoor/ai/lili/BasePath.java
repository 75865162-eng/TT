package com.wimoor.ai.lili;

/**
 * 移植自 src/lib/lili/base-path.ts：该模块仅做 routes.ts 常量与函数的再导出。
 * Java 没有 re-export 语法，这里以静态委托保持同一调用面。
 */
public final class BasePath {

    private BasePath() {}

    public static final String BASE_PATH = Routes.BASE_PATH;

    public static String withBasePath(String path) { return Routes.withBasePath(path); }
}
