package com.github.intfoo.agent;

import javassist.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

public class ReadActionTransformer implements ClassFileTransformer {

    // key: 类名(/)  value: 要包装的方法名列表
    private static final Map<String, List<String>> PATCH_MAP = new HashMap<>();

    static {
        // 在这里登记所有需要修复的类和方法，新出问题加一行就行
        register("cn/apipost/restful/debug/RestServiceDetail", "setApiService");
        register("cn/apipost/restful/debug/RestServiceDetail", "getModuleName");
        register("cn/apipost/restful/debug/RestServiceDetail", "saveMethod");
        register("cn/apipost/restful/debug/RestServiceDetail", "sendMethod");
        register("cn/apipost/restful/debug/CustomEditor", "createDocument");
        register("cn/apipost/restful/method/action/PropertiesHandler", "findPsiFileInModule");
        register("cn/apipost/utils/scanner/framework/Spring", "getAllParameter");
        register("cn/apipost/utils/scanner/framework/Spring", "getClassPath");
        register("cn/apipost/action/Class2JSONAction", "convertClassToJSON");
        register("cn/apipost/utils/ApiParser", "isParseTargetPsiClass");
        register("cn/apipost/utils/ApiParser", "getPsiAnnotation");
        register("cn/apipost/utils/ApiParser", "restServiceDetailParseApis");
        register("cn/apipost/utils/ApiParser", "classParseApis");
        register("cn/apipost/utils/ApiParser", "getResponse");
        register("cn/apipost/utils/ApiParser", "apiServiceParseApis");
        // register("cn/apipost/xxx/YyyClass",                  "someMethod");
    }

    private static void register(String clazz, String method) {
        PATCH_MAP.computeIfAbsent(clazz, k -> new ArrayList<>()).add(method);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> redefining, ProtectionDomain domain,
                            byte[] classBytes) {
        if (className == null || !PATCH_MAP.containsKey(className)) return null;

        List<String> methods = PATCH_MAP.get(className);
        try {
            ClassPool pool = new ClassPool(ClassPool.getDefault()); // 继承父池但独立

            // ★ 关键修复：把当前类的 ClassLoader 加进来，让 Javassist 能找到插件内的类
            if (loader != null) {
                pool.appendClassPath(new LoaderClassPath(loader));
            }

            pool.insertClassPath(new ByteArrayClassPath(
                    className.replace('/', '.'), classBytes));

            CtClass cc = pool.get(className.replace('/', '.'));
            boolean modified = false;

            for (CtMethod m : cc.getDeclaredMethods()) {
                if (!methods.contains(m.getName())) continue;
                wrapWithReadAction(m);
                modified = true;
                System.out.println("[ReadActionPatch] patched: "
                        + className + "#" + m.getName());
            }

            return modified ? cc.toBytecode() : null;

        } catch (Exception e) {
            System.err.println("[ReadActionPatch] failed: " + className);
            e.printStackTrace();
            return null;
        }
    }

    private void wrapWithReadAction(CtMethod method) throws Exception {
        CtClass declaring = method.getDeclaringClass();
        String originalName = "_original_" + method.getName();

        CtMethod copy = CtNewMethod.copy(method, originalName, declaring, null);
        copy.setModifiers(Modifier.PRIVATE
                | (Modifier.isStatic(method.getModifiers()) ? Modifier.STATIC : 0));
        declaring.addMethod(copy);

        CtClass[] paramTypes = method.getParameterTypes();
        CtClass returnType = method.getReturnType();
        boolean isVoid = returnType == CtClass.voidType;

        // 构造 paramTypes 数组字面量，例如：new Class[]{String.class, int.class}
        StringBuilder classArray = new StringBuilder("new Class[]{");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) classArray.append(", ");
            if (paramTypes[i].isPrimitive()) {
                classArray.append(getPrimitiveWrapper(paramTypes[i])).append(".TYPE");
            } else {
                classArray.append(paramTypes[i].getName()).append(".class");
            }
        }
        classArray.append("}");

        // 构造 params 数组字面量，例如：new Object[]{$1, $2}
        StringBuilder objArray = new StringBuilder("new Object[]{");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) objArray.append(", ");
            objArray.append("($w)$").append(i + 1);  // $w 在顶层可以用，负责基本类型装箱
        }
        objArray.append("}");

        StringBuilder body = new StringBuilder("{\n");

        if (isVoid) {
            body.append("  com.github.intfoo.agent.StaticHelper.runInReadAction(")
                    .append("this, \"").append(originalName).append("\", ")
                    .append(objArray).append(", ").append(classArray).append(");\n");
        } else if (returnType.isPrimitive()) {
            String wrapper = getPrimitiveWrapper(returnType);
            body.append("  return ((").append(wrapper).append(")")
                    .append(" com.github.intfoo.agent.StaticHelper.runInReadAction(")
                    .append("this, \"").append(originalName).append("\", ")
                    .append(objArray).append(", ").append(classArray).append("))")
                    .append(".").append(getPrimitiveUnboxMethod(returnType)).append("();\n");
        } else {
            body.append("  return (").append(returnType.getName()).append(")")
                    .append(" com.github.intfoo.agent.StaticHelper.runInReadAction(")
                    .append("this, \"").append(originalName).append("\", ")
                    .append(objArray).append(", ").append(classArray).append(");\n");
        }

        body.append("}");

        System.out.println("[ReadActionPatch] generated body for "
                + method.getLongName() + ":\n" + body);

        method.setBody(body.toString());
    }

    private String getPrimitiveUnboxMethod(CtClass p) {
        if (p == CtClass.intType) return "intValue";
        if (p == CtClass.longType) return "longValue";
        if (p == CtClass.booleanType) return "booleanValue";
        if (p == CtClass.doubleType) return "doubleValue";
        if (p == CtClass.floatType) return "floatValue";
        if (p == CtClass.shortType) return "shortValue";
        if (p == CtClass.byteType) return "byteValue";
        if (p == CtClass.charType) return "charValue";
        return "toString";
    }

    private String getPrimitiveWrapper(CtClass primitive) {
        if (primitive == CtClass.intType) return "java.lang.Integer";
        if (primitive == CtClass.longType) return "java.lang.Long";
        if (primitive == CtClass.booleanType) return "java.lang.Boolean";
        if (primitive == CtClass.doubleType) return "java.lang.Double";
        if (primitive == CtClass.floatType) return "java.lang.Float";
        if (primitive == CtClass.shortType) return "java.lang.Short";
        if (primitive == CtClass.byteType) return "java.lang.Byte";
        if (primitive == CtClass.charType) return "java.lang.Character";
        return "java.lang.Object";
    }
}