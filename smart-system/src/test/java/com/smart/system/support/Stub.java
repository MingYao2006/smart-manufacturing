package com.smart.system.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 Mapper 桩（JDK 动态代理），替代 Mockito。
 *
 * <p>Mockito 默认 MockMaker 通过 ByteBuddy self-attach 挂载 Java Agent（需派生外部进程），
 * 本开发环境无法派生进程，会报 "Could not self-attach to current VM"。
 * 改用 JDK 自带动态代理：零依赖、零字节码增强，受限环境稳定可跑。
 */
public final class Stub {

    private Stub() {
    }

    public record Invocation(Method method, Object[] args) {
        public String name() {
            return method.getName();
        }

        public int argCount() {
            return args == null ? 0 : args.length;
        }

        public Object arg(int i) {
            return args == null ? null : args[i];
        }

        public Object arg0() {
            return arg(0);
        }
    }

    @FunctionalInterface
    public interface Behavior {
        Object apply(Invocation inv) throws Throwable;
    }

    public record Rule(String methodName, int argCount, Behavior behavior) {
    }

    public static Rule rule(String methodName, int argCount, Behavior behavior) {
        return new Rule(methodName, argCount, behavior);
    }

    public static Rule rule(String methodName, Behavior behavior) {
        return new Rule(methodName, -1, behavior);
    }

    public static Behavior returns(Object value) {
        return inv -> value;
    }

    public static Behavior returnsInt(int value) {
        return inv -> value;
    }

    public static final class Recorder<T> {

        private final T proxy;
        private final List<String> calls = new ArrayList<>();
        private final Map<String, List<Object[]>> argsByMethod = new HashMap<>();

        private Recorder(T proxy) {
            this.proxy = proxy;
        }

        @SuppressWarnings("unchecked")
        public static <T> Recorder<T> of(Class<T> iface, Rule... rules) {
            List<Rule> ruleList = Arrays.asList(rules);
            Recorder<T>[] holder = new Recorder[1];

            InvocationHandler handler = (p, method, args) -> {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return iface.getSimpleName() + "$Stub";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(p);
                }
                if ("equals".equals(name)) {
                    return p == (args == null ? null : args[0]);
                }

                Invocation inv = new Invocation(method, args);
                Recorder<T> rec = holder[0];
                if (rec != null) {
                    rec.calls.add(name);
                    rec.argsByMethod.computeIfAbsent(name, k -> new ArrayList<>()).add(args);
                }

                for (Rule r : ruleList) {
                    if (!r.methodName().equals(name)) {
                        continue;
                    }
                    if (r.argCount() >= 0 && r.argCount() != inv.argCount()) {
                        continue;
                    }
                    return r.behavior().apply(inv);
                }
                return defaultValue(method.getReturnType());
            };

            T proxy = (T) Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class<?>[]{iface}, handler);
            Recorder<T> rec = new Recorder<>(proxy);
            holder[0] = rec;
            return rec;
        }

        public T proxy() {
            return proxy;
        }

        public int callCount(String methodName) {
            return (int) calls.stream().filter(methodName::equals).count();
        }

        public Object[] firstArgs(String methodName) {
            List<Object[]> list = argsByMethod.get(methodName);
            return list == null || list.isEmpty() ? new Object[0] : list.get(0);
        }

        public List<Object[]> allArgs(String methodName) {
            return argsByMethod.getOrDefault(methodName, List.of());
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (type == List.class || type == java.util.Collection.class) {
                return new ArrayList<>();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
