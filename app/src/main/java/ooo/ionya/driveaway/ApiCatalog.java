package ooo.ionya.driveaway;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ai.pleos.playground.vehicle.Vehicle;

/** Uses the SDK interfaces themselves as the source of truth, including legacy overloads. */
public final class ApiCatalog {
    public enum Kind { CAPABILITY, GET, CONTROL, REGISTER, UNREGISTER }
    public static final class Api {
        public final Method getter, method;
        public final String domain, key, permission;
        public final boolean deprecated;
        public final Kind kind;
        private final JSONArray parameterNames;
        Api(Method getter, Method method, JSONObject metadata) {
            this.getter = getter;
            this.method = method;
            domain = getter.getReturnType().getSimpleName();
            key = key(method);
            JSONObject info = metadata.optJSONObject(key);
            if (info == null) throw new IllegalStateException("Missing SDK metadata: " + key);
            permission = info.optString("permission");
            deprecated = info.optBoolean("deprecated");
            parameterNames = info.optJSONArray("parameters");
            String name = method.getName();
            if (name.startsWith("check")) kind = Kind.CAPABILITY;
            else if (name.startsWith("unregister")) kind = Kind.UNREGISTER;
            else if (name.startsWith("register")) kind = Kind.REGISTER;
            else if (name.startsWith("set") || name.startsWith("move") || name.startsWith("select")) kind = Kind.CONTROL;
            else kind = Kind.GET;
        }
        public String name(int index) {
            return parameterNames == null ? "arg" + index : parameterNames.optString(index, "arg" + index);
        }
        public String title() { return domain + "." + method.getName(); }
        public String signature() {
            List<String> params = new ArrayList<>();
            Type[] types = method.getGenericParameterTypes();
            for (int i = 0; i < types.length; i++) params.add(name(i) + ": " + shortType(types[i]));
            return method.getName() + "(" + String.join(", ", params) + ")";
        }
        public boolean hasSuccessCallback() {
            for (int i = 0; i < method.getParameterCount(); i++)
                if (isFunction(method.getParameterTypes()[i]) && !name(i).equals("onFailed")) return true;
            return false;
        }
        public boolean isFailure(int index) {
            // Metadata names preserve SDK onFailed even on methods with no success callback.
            return name(index).equals("onFailed") || method.getGenericParameterTypes()[index]
                    .getTypeName().contains("java.lang.Exception");
        }
    }
    public final List<Api> all;
    public final List<String> domains;
    public ApiCatalog(Context context) throws Exception {
        JSONObject metadata;
        try (java.io.InputStream input = context.getAssets().open("api-metadata.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            metadata = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
        List<Api> entries = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        for (Method getter : Vehicle.class.getDeclaredMethods()) {
            Class<?> type = getter.getReturnType();
            if (getter.getParameterCount() != 0 || !type.isInterface()
                    || !type.getName().startsWith("ai.pleos.playground.vehicle.api.")) continue;
            names.add(type.getSimpleName());
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) continue;
                entries.add(new Api(getter, method, metadata));
            }
        }
        entries.sort(Comparator.comparing((Api a) -> a.domain).thenComparing(a -> a.kind)
                .thenComparing(a -> a.method.getName()).thenComparing(a -> a.key));
        if (entries.size() != metadata.length()) throw new IllegalStateException(
                "SDK/metadata mismatch: " + entries.size() + " / " + metadata.length());
        all = Collections.unmodifiableList(entries);
        domains = Collections.unmodifiableList(new ArrayList<>(names));
    }
    public static boolean isFunction(Class<?> type) { return type.getName().startsWith("kotlin.jvm.functions.Function"); }
    public static boolean isListener(Class<?> type) { return type.getName().startsWith("ai.pleos.playground.vehicle.listener."); }
    public static boolean isArea(Class<?> type) { return type.getName().startsWith("ai.pleos.playground.vehicle.api.model.area."); }
    public static Type unwrap(Type type) {
        return type instanceof WildcardType ? ((WildcardType) type).getUpperBounds()[0] : type;
    }
    public static Class<?> listElement(Type type) {
        return (Class<?>) unwrap(((ParameterizedType) type).getActualTypeArguments()[0]);
    }
    public static boolean supports(Type generic) {
        Class<?> type = generic instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) generic).getRawType() : (Class<?>) generic;
        if (isFunction(type) || isListener(type)) return type.isInterface();
        if (type == boolean.class || type == int.class || type == float.class || type.isEnum()) return true;
        if (isArea(type)) {
            for (Constructor<?> constructor : type.getConstructors()) {
                Class<?>[] args = constructor.getParameterTypes();
                if (args.length == 1 && args[0].isArray() && args[0].getComponentType().isEnum()) return true;
            }
        }
        return type == List.class && generic instanceof ParameterizedType && listElement(generic).isEnum();
    }
    public static String shortType(Type type) {
        return type.getTypeName().replaceAll("[a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*\\.", "");
    }
    public static String key(Method method) {
        StringBuilder text = new StringBuilder(method.getDeclaringClass().getName() + "#" + method.getName() + "(");
        for (Class<?> type : method.getParameterTypes()) text.append(descriptor(type));
        return text.append(')').append(descriptor(method.getReturnType())).toString();
    }
    private static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == int.class) return "I";
        if (type == float.class) return "F";
        if (type == boolean.class) return "Z";
        throw new IllegalArgumentException(type.getName());
    }
}
