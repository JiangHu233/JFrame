package io.github.JiangHu.jframe.transform.registry;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 目标规格——一次注册的类/方法匹配器（类名/方法名支持 {@code *} 简单通配，
 * 可选参数类型列表消除重载歧义）。
 */
final class TargetSpec {

    /** 注册 id（句柄簿记） */
    final int regId;

    /** 类名模式（{@code *} 通配） */
    final String classPattern;

    /** 方法名模式（{@code *} 通配） */
    final String methodPattern;

    /** 参数类型全限定名（null = 任意重载；元素可为基本类型名如 {@code int}） */
    final List<String> paramTypes;

    private final Pattern classRegex;
    private final Pattern methodRegex;

    TargetSpec(int regId, String classPattern, String methodPattern, List<String> paramTypes) {
        this.regId = regId;
        this.classPattern = classPattern;
        this.methodPattern = methodPattern;
        this.paramTypes = paramTypes;
        this.classRegex = compile(classPattern);
        this.methodRegex = compile(methodPattern);
    }

    /**
     * 类名是否匹配。
     */
    boolean matchesClass(String className) {
        return classRegex.matcher(className).matches();
    }

    /**
     * 方法名是否匹配。
     */
    boolean matchesMethod(String methodName) {
        return methodRegex.matcher(methodName).matches();
    }

    /**
     * 参数列表是否匹配（未声明参数 = 任意；声明则逐名相等，含基本类型名）。
     */
    boolean matchesParams(List<String> paramTypeNames) {
        if (paramTypes == null) {
            return true;
        }
        if (paramTypes.size() != paramTypeNames.size()) {
            return false;
        }
        for (int i = 0; i < paramTypes.size(); i++) {
            if (!paramTypes.get(i).equals(paramTypeNames.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 简单通配编译：字面量 + {@code *} → 正则。
     */
    private static Pattern compile(String pattern) {
        String[] parts = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            if (!parts[i].isEmpty()) {
                regex.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
