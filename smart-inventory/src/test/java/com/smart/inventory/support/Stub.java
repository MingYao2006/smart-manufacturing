package com.smart.inventory.support;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 Mapper 桩（基于 JDK 动态代理），用于替代 Mockito。
 *
 * <h3>为什么不用 Mockito</h3>
 * Mockito 默认的 MockMaker 通过 ByteBuddy 的 <b>self-attach</b> 机制挂载 Java Agent
 * （需要派生外部进程）。在本项目开发环境（受限沙箱）中无法派生进程，会报：
 * <pre>
 *   IllegalStateException: Could not self-attach to current VM using external process
 * </pre>
 * 改用 JDK 自带的动态代理手写桩 —— 零依赖、零字节码增强，在受限环境稳定可跑。
 * <b>这也是一个可讲的取舍：测试替身不必依赖 Mockito。</b>
 *
 * <h3>用法</h3>
 * <pre>
 *   Recorder&lt;MaterialInventoryMapper&gt; inv = Recorder.of(MaterialInventoryMapper.class,
 *       Stub.rule("selectOne", (i) -&gt; inventory),
 *       Stub.rule("reserveStock", (i) -&gt; 1));
 *   inventoryService = new InventoryService(inv.proxy(), ...);
 *   inv.callCount("reserveStock");   // 断言调用次数
 * </pre>
 */
public final class Stub {

    private Stub() {
    }

    /**
     * 方法调用上下文。
     */
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

        /** 第 0 个参数（常见：单个 id 的场景） */
        public Object arg0() {
            return arg(0);
        }
    }

    /**
     * 行为：给定调用返回结果。
     */
    @FunctionalInterface
    public interface Behavior {
        Object apply(Invocation inv) throws Throwable;
    }

    /**
     * 规则：方法名 + 参数个数（-1 表示不限）+ 行为。
     */
    public record Rule(String methodName, int argCount, Behavior behavior) {
    }

    /** 按方法名 + 精确参数个数匹配 */
    public static Rule rule(String methodName, int argCount, Behavior behavior) {
        return new Rule(methodName, argCount, behavior);
    }

    /** 按方法名匹配（任意参数个数） */
    public static Rule rule(String methodName, Behavior behavior) {
        return new Rule(methodName, -1, behavior);
    }

    /** 固定返回值 */
    public static Behavior returns(Object value) {
        return inv -> value;
    }

    /** 固定返回 int（便于桩"影响行数"） */
    public static Behavior returnsInt(int value) {
        return inv -> value;
    }

    /**
     * 带调用记录的桩。
     */
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
            // 先建 holder，让 handler 与 Recorder 共享同一份记录容器
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

        public boolean wasCalled(String methodName) {
            return callCount(methodName) > 0;
        }

        /** 某方法第一次调用时的参数 */
        public Object[] firstArgs(String methodName) {
            List<Object[]> list = argsByMethod.get(methodName);
            return list == null || list.isEmpty() ? new Object[0] : list.get(0);
        }

        /** 某方法所有调用的参数 */
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
