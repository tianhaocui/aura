package io.aura.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;

/** Records the single parameter a {@link SqlBindable} binds, without a database. */
final class BindingRecorder implements InvocationHandler {

    int index = -1;
    Object value;
    int sqlType = Integer.MIN_VALUE;

    static BindingRecorder bind(SqlBindable parameter) throws Exception {
        BindingRecorder recorder = new BindingRecorder();
        parameter.bind(recorder.statement(), 2);
        return recorder;
    }

    PreparedStatement statement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("setObject") && args != null && args.length == 3) {
            index = (Integer) args[0];
            value = args[1];
            sqlType = (Integer) args[2];
        }
        return null;
    }
}
