package com.back.coffeeprod.performance;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Captures SQL templates only, never JDBC values, HTTP bodies or tokens. Diagnostics only. */
public final class SqlCapture implements StatementInspector {
    private static final Queue<String> SQL = new ConcurrentLinkedQueue<>();
    public static void reset() { SQL.clear(); }
    public static List<String> templates() { return List.copyOf(SQL); }
    @Override public String inspect(String sql) { SQL.add(sql); return sql; }
}
