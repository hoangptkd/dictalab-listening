package com.dictalab.support;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Counts executed SQL, including Hibernate and JdbcTemplate, only inside the calling thread's window. */
@TestConfiguration(proxyBeanMethods = false)
public class JdbcQueryCounter {
    private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> CONTROLS = new ThreadLocal<>();

    public static void start() { SQL.set(new ArrayList<>()); CONTROLS.set(new ArrayList<>()); }
    public static List<String> connectionControls() { return List.copyOf(CONTROLS.get()); }
    public static List<String> stop() {
        List<String> result = List.copyOf(SQL.get());
        SQL.remove();
        CONTROLS.remove();
        return result;
    }
    public static long selects(List<String> sql) {
        return sql.stream().filter(s -> s.startsWith("select") || s.startsWith("with")).count();
    }

    @Bean static BeanPostProcessor countDataSourceSql() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource source)) return bean;
                return new DelegatingDataSource(source) {
                    @Override public Connection getConnection() throws java.sql.SQLException {
                        return wrap(super.getConnection());
                    }
                    @Override public Connection getConnection(String user, String password) throws java.sql.SQLException {
                        return wrap(super.getConnection(user, password));
                    }
                };
            }
        };
    }

    private static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (CONTROLS.get() != null && List.of("setAutoCommit", "setReadOnly", "commit", "rollback").contains(method.getName()))
                        CONTROLS.get().add(method.getName() + (args == null ? "" : java.util.Arrays.toString(args)));
                    Object result = invoke(connection, method, args);
                    if (!(result instanceof Statement statement)) return result;
                    String prepared = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{method.getReturnType()},
                            (p, m, a) -> {
                                if (m.getName().startsWith("execute") && SQL.get() != null) {
                                    String sql = a != null && a.length > 0 && a[0] instanceof String s ? s : prepared;
                                    if (sql != null) SQL.get().add(sql.stripLeading().toLowerCase(Locale.ROOT));
                                }
                                return invoke(statement, m, a);
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
