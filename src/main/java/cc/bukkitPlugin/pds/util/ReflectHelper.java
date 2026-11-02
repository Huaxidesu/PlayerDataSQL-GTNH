package cc.bukkitPlugin.pds.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 轻量反射工具
 * <p>
 * 数据模型需要访问模组的私有状态，而模组类不在编译期依赖里，只能用反射。<br>
 * 这里只提供最小的一组包装：统一把受检异常收敛成 {@link IllegalStateException}，<br>
 * 让模型代码能保持可读的调用链，异常再由各自的业务逻辑捕获。
 * </p>
 */
public class ReflectHelper {

    private ReflectHelper() {}

    public static Object invoke(Method pMethod, Object pTarget, Object...pArgs) {
        try {
            return pMethod.invoke(pTarget, pArgs);
        } catch (InvocationTargetException exp) {
            throw raise(exp.getCause());
        } catch (Throwable exp) {
            throw raise(exp);
        }
    }

    public static Object construct(Constructor<?> pCtor, Object...pArgs) {
        try {
            return pCtor.newInstance(pArgs);
        } catch (InvocationTargetException exp) {
            throw raise(exp.getCause());
        } catch (Throwable exp) {
            throw raise(exp);
        }
    }

    public static Object get(Field pField, Object pTarget) {
        try {
            return pField.get(pTarget);
        } catch (Throwable exp) {
            throw raise(exp);
        }
    }

    public static void set(Field pField, Object pTarget, Object pValue) {
        try {
            pField.set(pTarget, pValue);
        } catch (Throwable exp) {
            throw raise(exp);
        }
    }

    private static RuntimeException raise(Throwable pExp) {
        if (pExp instanceof RuntimeException) return (RuntimeException)pExp;
        return new IllegalStateException(pExp);
    }

}
