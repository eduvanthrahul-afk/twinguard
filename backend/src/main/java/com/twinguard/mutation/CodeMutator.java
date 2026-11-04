package com.twinguard.mutation;

import com.twinguard.sim.BrakeController;
import com.twinguard.sim.BrakingLogic;
import com.twinguard.sim.Firmware;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * Creates mutants by rewriting one bytecode instruction of a logic class. Each mutant is loaded in
 * its own class loader, so the approved version always runs the original code.
 */
public final class CodeMutator {

    public static final String NEGATE = "Reversed condition";
    public static final String BOUNDARY = "Comparison boundary";
    public static final String MATH = "Arithmetic operator";
    public static final String CONSTANT = "Changed constant";
    public static final String RETURN_ZERO = "Return value";

    private static final double CONSTANT_SCALE = 1.5;

    private static final Map<Integer, Integer> NEGATED = Map.ofEntries(
            Map.entry(Opcodes.IFEQ, Opcodes.IFNE), Map.entry(Opcodes.IFNE, Opcodes.IFEQ),
            Map.entry(Opcodes.IFLT, Opcodes.IFGE), Map.entry(Opcodes.IFGE, Opcodes.IFLT),
            Map.entry(Opcodes.IFGT, Opcodes.IFLE), Map.entry(Opcodes.IFLE, Opcodes.IFGT),
            Map.entry(Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE), Map.entry(Opcodes.IF_ICMPNE, Opcodes.IF_ICMPEQ),
            Map.entry(Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE), Map.entry(Opcodes.IF_ICMPGE, Opcodes.IF_ICMPLT),
            Map.entry(Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE), Map.entry(Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT));

    private static final Map<Integer, Integer> BOUNDARIES = Map.of(
            Opcodes.IFLT, Opcodes.IFLE, Opcodes.IFLE, Opcodes.IFLT,
            Opcodes.IFGT, Opcodes.IFGE, Opcodes.IFGE, Opcodes.IFGT,
            Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPLT,
            Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT);

    private static final Map<Integer, Integer> ARITHMETIC = Map.of(
            Opcodes.DADD, Opcodes.DSUB, Opcodes.DSUB, Opcodes.DADD,
            Opcodes.DMUL, Opcodes.DDIV, Opcodes.DDIV, Opcodes.DMUL,
            Opcodes.IADD, Opcodes.ISUB, Opcodes.ISUB, Opcodes.IADD,
            Opcodes.IMUL, Opcodes.IDIV, Opcodes.IDIV, Opcodes.IMUL,
            Opcodes.IREM, Opcodes.IMUL);

    private static final Map<Integer, String> SYMBOLS = Map.of(
            Opcodes.DADD, "+", Opcodes.IADD, "+", Opcodes.DSUB, "-", Opcodes.ISUB, "-",
            Opcodes.DMUL, "*", Opcodes.IMUL, "*", Opcodes.DDIV, "/", Opcodes.IDIV, "/", Opcodes.IREM, "%");

    private static final Map<Integer, Integer> CONSTANTS = Map.of(
            Opcodes.DCONST_0, Opcodes.DCONST_1, Opcodes.DCONST_1, Opcodes.DCONST_0,
            Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_1, Opcodes.ICONST_0);

    private static final List<CodeMutant> BRAKING = buildBraking();

    private CodeMutator() {
    }

    /** One bug in any logic class: where it is, what it changes, and how to create the changed class. */
    public record Generated(String id, String method, int line, String operator, String description, String sourceLine, Constructor<?> constructor) {
    }

    public static List<CodeMutant> all() {
        return BRAKING;
    }

    public static Optional<CodeMutant> find(String id) {
        return BRAKING.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    private static List<CodeMutant> buildBraking() {
        List<CodeMutant> out = new ArrayList<>();
        for (Generated g : generate(BrakingLogic.class, Firmware.class, double.class)) {
            Firmware.ControllerFactory factory = (firmware, friction) -> (BrakeController) instantiate(g.constructor(), firmware, friction);
            out.add(new CodeMutant(g.id(), g.method(), g.line(), g.operator(), g.description(), g.sourceLine(), Firmware.approved().withController(factory)));
        }
        return List.copyOf(out);
    }

    /** Creates every mutant of a class. The class's constructor is never changed. */
    public static List<Generated> generate(Class<?> target, Class<?>... constructorTypes) {
        String resource = target.getName().replace('.', '/') + ".class";
        byte[] original = readBytes(target, resource);
        List<String> source = sourceLines(target);
        List<Site> sites = new ArrayList<>();
        new ClassReader(original).accept(new Rewriter(new ClassWriter(0), -1, sites), 0);

        List<Generated> out = new ArrayList<>(sites.size());
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            String text = site.line() >= 1 && site.line() <= source.size() ? source.get(site.line() - 1).trim() : "";
            out.add(new Generated(String.format("M%03d", i + 1), site.method(), site.line(), site.operator(), site.description(), text,
                    constructorFor(target, mutate(original, i), constructorTypes)));
        }
        return List.copyOf(out);
    }

    private record Site(String method, int line, String operator, String description) {
    }

    private static byte[] mutate(byte[] original, int index) {
        ClassReader reader = new ClassReader(original);
        // Each rewrite keeps the stack layout, so the existing frames stay valid.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new Rewriter(writer, index, null), 0);
        return writer.toByteArray();
    }

    private static Constructor<?> constructorFor(Class<?> target, byte[] bytes, Class<?>[] types) {
        try {
            return new MutantLoader(target, bytes).loadClass(target.getName()).getConstructor(types);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not load a mutant", e);
        }
    }

    public static Object instantiate(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("A mutant failed to start", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not create a mutant", e);
        }
    }

    private static byte[] readBytes(Class<?> target, String resource) {
        try (InputStream in = target.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    private static List<String> sourceLines(Class<?> target) {
        String resource = "mutation-source/" + target.getSimpleName() + ".java";
        try (InputStream in = target.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? List.of() : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Defines the mutated class itself, and leaves every other class to the normal loader. */
    private static final class MutantLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        MutantLoader(Class<?> target, byte[] bytes) {
            super(target.getClassLoader());
            this.name = target.getName();
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
            if (!className.equals(name)) {
                return super.loadClass(className, resolve);
            }
            synchronized (getClassLoadingLock(className)) {
                Class<?> loaded = findLoadedClass(className);
                return loaded != null ? loaded : defineClass(className, bytes, 0, bytes.length);
            }
        }
    }

    /** Numbers every possible mutation in a fixed order. It records them, or applies one of them. */
    private static final class Rewriter extends ClassVisitor {
        private final int target;
        private final List<Site> sites;
        private int counter;

        Rewriter(ClassVisitor next, int target, List<Site> sites) {
            super(Opcodes.ASM9, next);
            this.target = target;
            this.sites = sites;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
            boolean generated = (access & Opcodes.ACC_SYNTHETIC) != 0;
            return name.startsWith("<") || generated ? next : new Method(next, name);
        }

        /** Returns true if this numbered mutation is the one to apply. */
        private boolean claim(String method, int line, String operator, String description) {
            if (sites != null) {
                sites.add(new Site(method, line, operator, description));
            }
            return counter++ == target;
        }

        private final class Method extends MethodVisitor {
            private final String name;
            private int line;
            private int previous = -1;

            Method(MethodVisitor next, String name) {
                super(Opcodes.ASM9, next);
                this.name = name;
            }

            @Override
            public void visitLineNumber(int number, Label start) {
                line = number;
                super.visitLineNumber(number, start);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                int replacement = opcode;
                if (NEGATED.containsKey(opcode) && claim(name, line, NEGATE, "Reversed the outcome of this condition")) {
                    replacement = NEGATED.get(opcode);
                }
                if (BOUNDARIES.containsKey(opcode) && claim(name, line, BOUNDARY, "Changed this comparison to include or exclude equality")) {
                    replacement = BOUNDARIES.get(opcode);
                }
                previous = opcode;
                super.visitJumpInsn(replacement, label);
            }

            @Override
            public void visitInsn(int opcode) {
                int replacement = opcode;
                boolean zeroReturn = false;
                if (ARITHMETIC.containsKey(opcode)) {
                    int to = ARITHMETIC.get(opcode);
                    String description = "Changed " + SYMBOLS.get(opcode) + " to " + SYMBOLS.get(to);
                    if (claim(name, line, MATH, description)) {
                        replacement = to;
                    }
                } else if (CONSTANTS.containsKey(opcode)) {
                    boolean isOne = opcode == Opcodes.DCONST_1 || opcode == Opcodes.ICONST_1;
                    String description = "Changed the constant " + (isOne ? "1 to 0" : "0 to 1") + " (for a flag, true and false swap)";
                    if (claim(name, line, CONSTANT, description)) {
                        replacement = CONSTANTS.get(opcode);
                    }
                } else if (opcode == Opcodes.DRETURN && previous != Opcodes.DCONST_0
                        && claim(name, line, RETURN_ZERO, "Made this statement return 0")) {
                    zeroReturn = true;
                }
                if (zeroReturn) {
                    super.visitInsn(Opcodes.POP2);
                    super.visitInsn(Opcodes.DCONST_0);
                }
                previous = opcode;
                super.visitInsn(replacement);
            }

            @Override
            public void visitLdcInsn(Object value) {
                Object replacement = value;
                // Scaling infinity changes nothing, so it would not be a real mutant.
                if (value instanceof Double d && !d.isInfinite()) {
                    double scaled = Math.round(d * CONSTANT_SCALE * 1e6) / 1e6;
                    if (claim(name, line, CONSTANT, "Changed the constant " + d + " to " + scaled)) {
                        replacement = scaled;
                    }
                }
                previous = Opcodes.LDC;
                super.visitLdcInsn(replacement);
            }

            @Override
            public void visitVarInsn(int opcode, int index) {
                previous = opcode;
                super.visitVarInsn(opcode, index);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String field, String descriptor) {
                previous = opcode;
                super.visitFieldInsn(opcode, owner, field, descriptor);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                previous = opcode;
                super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
            }
        }
    }
}
