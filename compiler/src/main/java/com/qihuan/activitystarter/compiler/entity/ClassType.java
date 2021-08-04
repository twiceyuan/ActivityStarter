package com.qihuan.activitystarter.compiler.entity;

import com.qihuan.activitystarter.compiler.utils.TypeUtils;
import com.squareup.javapoet.TypeName;

/**
 * ClassType
 *
 * @author qi
 * @since 2021/8/4
 */
public class ClassType {
    private final String className;

    public ClassType(String className) {
        this.className = className;
    }

    public TypeName java() {
        return TypeUtils.getJavaTypeName(className);
    }
}
