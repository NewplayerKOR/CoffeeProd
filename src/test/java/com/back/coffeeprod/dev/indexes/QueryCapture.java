package com.back.coffeeprod.dev.indexes;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;

/** Captures only explicitly selected synthetic reads; never installed in the application. */
public final class QueryCapture {
    private QueryCapture() {}
    public record Binding(Object value, int type) {}
    public record Query(String sql, Map<Integer, Binding> bindings) {
        PreparedStatement prepare(Connection c, String prefix) throws SQLException {
            var s = c.prepareStatement(prefix + sql);
            for (var b : bindings.entrySet()) {
                if (b.getValue().value() == null) s.setNull(b.getKey(), b.getValue().type());
                else s.setObject(b.getKey(), b.getValue().value());
            }
            return s;
        }
    }
    private static final ThreadLocal<List<Query>> CURRENT = new ThreadLocal<>();
    static void start() { CURRENT.set(new ArrayList<>()); }
    static List<Query> stop() { var queries = List.copyOf(CURRENT.get()); CURRENT.remove(); return queries; }
    interface After { Object apply(Method method, Object[] args, Object result) throws Exception; }
    private static Object proxy(Class<?> type, Object target, After after) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            try { return after.apply(m, a, m.invoke(target, a)); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
    private static Connection connection(Connection c) {
        return (Connection) proxy(Connection.class, c, (m, a, result) -> {
            if (!m.getName().equals("prepareStatement") || !(a[0] instanceof String sql)) return result;
            Map<Integer, Binding> bindings = new TreeMap<>();
            return proxy(PreparedStatement.class, result, (method, args, value) -> {
                if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                    if (args[1] instanceof java.sql.Array) throw new IllegalArgumentException("Unexpected array binding");
                    bindings.put(index, new Binding(method.getName().equals("setNull") ? null : args[1],
                            method.getName().equals("setNull") ? (Integer) args[1] : Types.OTHER));
                }
                if (method.getName().equals("clearParameters")) bindings.clear();
                if (CURRENT.get() != null && method.getName().startsWith("execute") && sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))
                    CURRENT.get().add(new Query(sql, Collections.unmodifiableMap(new TreeMap<>(bindings))));
                return value;
            });
        });
    }
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean static BeanPostProcessor captureDataSource() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource)) return bean;
                    return proxy(DataSource.class, bean, (m, a, result) -> m.getName().equals("getConnection")
                            ? connection((Connection) result) : result);
                }
            };
        }
    }
}
