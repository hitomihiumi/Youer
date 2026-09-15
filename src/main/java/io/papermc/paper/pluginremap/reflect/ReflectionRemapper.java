package io.papermc.paper.pluginremap.reflect;

import io.papermc.asm.ClassInfoProvider;
import io.papermc.asm.RewriteRuleVisitorFactory;
import io.papermc.paper.util.MappingEnvironment;
import io.papermc.reflectionrewriter.BaseReflectionRules;
import io.papermc.reflectionrewriter.DefineClassRule;
import io.papermc.reflectionrewriter.proxygenerator.ProxyGenerator;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

@DefaultQualifier(NonNull.class)
public final class ReflectionRemapper {
    private static final String PAPER_REFLECTION_HOLDER = "io.papermc.paper.pluginremap.reflect.PaperReflectionHolder";
    private static final String PAPER_REFLECTION_HOLDER_DESC = PAPER_REFLECTION_HOLDER.replace('.', '/');
    private static final RewriteRuleVisitorFactory VISITOR_FACTORY = RewriteRuleVisitorFactory.create(
        Opcodes.ASM9,
        chain -> chain.then(new BaseReflectionRules(PAPER_REFLECTION_HOLDER).rules())
            .then(DefineClassRule.create(PAPER_REFLECTION_HOLDER_DESC, true)),
        ClassInfoProvider.basic()
    );

    static {
        if (!MappingEnvironment.reobf()) {
            setupProxy();
        }
    }

    private ReflectionRemapper() {
    }

    public static ClassVisitor visitor(final ClassVisitor parent) {
        if (MappingEnvironment.reobf() || MappingEnvironment.DISABLE_PLUGIN_REMAPPING) {
            return parent;
        }
        return VISITOR_FACTORY.createVisitor(parent);
    }

    public static byte[] processClass(final byte[] bytes) {
        if (MappingEnvironment.DISABLE_PLUGIN_REMAPPING) {
            return bytes;
        }
        final ClassReader classReader = new ClassReader(bytes);
        final ClassWriter classWriter = new ClassWriter(classReader, 0);
        classReader.accept(ReflectionRemapper.visitor(classWriter), 0);
        return classWriter.toByteArray();
    }

    private static void setupProxy() {
        try {
            final byte[] bytes = ProxyGenerator.generateProxy(
                classReader(PaperReflection.class), PAPER_REFLECTION_HOLDER_DESC, parentReaders(PaperReflection.class)); // Youer
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final Class<?> generated = lookup.defineClass(bytes);
            final Method init = generated.getDeclaredMethod("init", PaperReflection.class);
            init.invoke(null, new PaperReflection());
        } catch (final ReflectiveOperationException | java.io.IOException ex) { // Youer - classReader
            throw new RuntimeException(ex);
        }
    }

    // Youer start - read the proxied class through its own class loader
    // ProxyGenerator.generateProxy(Class, String) reads class files through ProxyGenerator's own class
    // loader. Under FML that is the boot layer, which cannot see PaperReflection in the game layer, so
    // the read returns null and every plugin fails to load. Drive the ClassReader overload instead and
    // resolve each class through the loader that actually defined it. The parent walk mirrors
    // ProxyGenerator's own: superclasses up to (but not including) Object, plus all interfaces.
    private static ClassReader[] parentReaders(final Class<?> type) throws java.io.IOException {
        final java.util.Set<Class<?>> parents = new java.util.LinkedHashSet<>();
        collectParents(parents, type);
        final ClassReader[] readers = new ClassReader[parents.size()];
        int i = 0;
        for (final Class<?> parent : parents) {
            readers[i++] = classReader(parent);
        }
        return readers;
    }

    private static void collectParents(final java.util.Set<Class<?>> into, final Class<?> type) {
        final Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class && into.add(superclass)) {
            collectParents(into, superclass);
        }
        for (final Class<?> iface : type.getInterfaces()) {
            if (into.add(iface)) {
                collectParents(into, iface);
            }
        }
    }

    private static ClassReader classReader(final Class<?> type) throws java.io.IOException {
        final String resource = type.getName().replace('.', '/') + ".class";
        final ClassLoader loader = type.getClassLoader();
        try (final java.io.InputStream in = loader == null
            ? ClassLoader.getSystemResourceAsStream(resource)
            : loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new java.io.IOException("Could not read class '" + type.getName() + "'");
            }
            return new ClassReader(in);
        }
    }
    // Youer end - read the proxied class through its own class loader
}
