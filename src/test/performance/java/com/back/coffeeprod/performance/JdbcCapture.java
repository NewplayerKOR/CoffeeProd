package com.back.coffeeprod.performance;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Diagnostic SELECT replay only. Values are captured only during candidate 8's synthetic reads. */
@Configuration(proxyBeanMethods=false) @Profile("perf")
public class JdbcCapture {
    public record Query(String sql,Map<Integer,Object> arguments,Map<Integer,Integer> nullTypes) {}
    public record ArrayValue(String type,List<Object> values) {}
    static Object copyBinding(Object value)throws SQLException {
        if(value instanceof java.sql.Array array)return new ArrayValue(array.getBaseTypeName(),Arrays.asList((Object[])array.getArray()));
        return value;
    }
    private static volatile boolean active;
    private static final Queue<Query> queries=new ConcurrentLinkedQueue<>();
    public static void start(){queries.clear();active=true;}
    public static List<Query> stop(){active=false;return List.copyOf(queries);}
    @Bean static BeanPostProcessor diagnosticDataSource() {
        return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if(!(bean instanceof DataSource)||!Boolean.parseBoolean(System.getenv("PERF_DIAGNOSTIC")))return bean;
                return proxy(DataSource.class,bean,(method,args,result)->method.getName().equals("getConnection")?connection((Connection)result):result);
            }
        };
    }
    interface After {Object apply(Method method,Object[] args,Object result)throws Exception;}
    static Object proxy(Class<?> type,Object target,After after){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
        try{return after.apply(m,a,m.invoke(target,a));}catch(InvocationTargetException e){throw e.getCause();}
    });}
    static Connection connection(Connection target){return(Connection)proxy(Connection.class,target,(m,args,value)->{
        if(!m.getName().equals("prepareStatement")||!(args[0] instanceof String sql))return value;
        Map<Integer,Object> bindings=new TreeMap<>();
        Map<Integer,Integer> types=new TreeMap<>();
        return proxy(PreparedStatement.class,value,(method,a,result)->{
            if(method.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer index) {
                bindings.put(index,method.getName().equals("setNull")?null:copyBinding(a[1]));
                if(method.getName().equals("setNull"))types.put(index,(Integer)a[1]);
                else if(method.getName().equals("setString"))types.put(index,Types.VARCHAR);
                else if(method.getName().equals("setObject")&&a.length>=3&&a[2] instanceof Integer sqlType)types.put(index,sqlType);
            }
            if(method.getName().equals("clearParameters")){bindings.clear();types.clear();}
            if(active&&method.getName().startsWith("execute")&&sql.stripLeading().toLowerCase(Locale.ROOT).startsWith("select"))
                queries.add(new Query(sql,Collections.unmodifiableMap(new TreeMap<>(bindings)),Map.copyOf(types)));
            return result;
        });
    });}
    public static List<Map<String,Object>> explain(JdbcTemplate jdbc,List<Query> captured){
        var plans=new ArrayList<Map<String,Object>>();
        for(var q:captured){
            // Candidate 8 has no login/token requests and executes only these synthetic read paths.
            if(!q.sql().toLowerCase(Locale.ROOT).matches("(?s).*(product|reviews|orders|payment|sales_statistics).*"))continue;
            String plan=jdbc.execute((ConnectionCallback<String>)c->{try(var s=c.prepareStatement("explain (analyze,buffers,format json) "+q.sql())){
                for(var b:q.arguments().entrySet()) {
                    if(b.getValue()==null)s.setNull(b.getKey(),q.nullTypes().getOrDefault(b.getKey(),Types.NULL));
                    else if(b.getValue() instanceof ArrayValue array)s.setArray(b.getKey(),c.createArrayOf(array.type(),array.values().toArray()));
                    else s.setObject(b.getKey(),b.getValue());
                }
                try(var rows=s.executeQuery()){rows.next();return rows.getString(1);}
            }});
            plans.add(Map.of("sql",q.sql(),"bindings",q.arguments(),"plan",plan));
        }
        if(plans.isEmpty())throw new IllegalStateException("No actual service SELECT plans captured");
        return plans;
    }
}
