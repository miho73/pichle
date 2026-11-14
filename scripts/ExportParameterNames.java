import java.lang.reflect.*;
import java.util.*;
import kotlin.reflect.*;
import kotlin.reflect.jvm.ReflectJvmMapping;

/** Build-time helper: preserve SDK Kotlin parameter names without runtime reflection metadata. */
public class ExportParameterNames {
    static String descriptor(Class<?> c) {
        if (c.isArray()) return c.getName().replace('.', '/');
        if (!c.isPrimitive()) return "L" + c.getName().replace('.', '/') + ";";
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == int.class) return "I";
        if (c == float.class) return "F";
        throw new IllegalArgumentException(c.getName());
    }
    public static void main(String[] classes) throws Exception {
        for (String name : classes) {
            for (Method m : Class.forName(name, false, ExportParameterNames.class.getClassLoader()).getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                StringBuilder key = new StringBuilder(name + "#" + m.getName() + "(");
                for (Class<?> p : m.getParameterTypes()) key.append(descriptor(p));
                key.append(")").append(descriptor(m.getReturnType()));
                KFunction<?> function = ReflectJvmMapping.getKotlinFunction(m);
                List<String> names = new ArrayList<>();
                if (function != null) for (KParameter p : function.getParameters()) {
                    if (p.getKind() == KParameter.Kind.VALUE) names.add(p.getName());
                }
                System.out.println(key + "\t" + String.join(",", names));
            }
        }
    }
}
