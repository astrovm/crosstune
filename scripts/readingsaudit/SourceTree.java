import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.kdoc.psi.api.KDoc;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.*;

/**
 * The readings guard's view of a source file, from the Kotlin compiler's own parser: Kotlin with its PSI, Java with
 * the Java PSI the compiler carries. It reads syntax and scopes, not types: for each name it says which of the file's
 * own declarations can bind it, and whether one of them surely does. source_calls.py follows the rest across files.
 *
 * A request on stdin is a line "PATH_BYTES TEXT_BYTES", then the path and the text in UTF-8. The answer is one line
 * of JSON on stdout. The worker ends when stdin closes.
 */
public final class SourceTree {

    public static void main(String[] args) throws IOException {
        Disposable disposable = Disposer.newDisposable();
        try {
            CompilerConfiguration configuration = new CompilerConfiguration();
            org.jetbrains.kotlin.cli.FrontendConfigurationKeysKt.setExtensionsStorage(configuration, new CompilerPluginRegistrar.ExtensionStorage());
            Project project = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES).getProject();
            KtPsiFactory kotlin = new KtPsiFactory(project, false);
            PsiFileFactory java = PsiFileFactory.getInstance(project);
            InputStream in = new BufferedInputStream(System.in);
            OutputStream out = new BufferedOutputStream(System.out);
            out.write(("{\"ready\":\"" + System.getProperty("java.version") + "\"}\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            while (true) {
                String header = line(in);
                if (header == null) return;
                String[] sizes = header.trim().split(" ");
                String path = new String(in.readNBytes(Integer.parseInt(sizes[0])), StandardCharsets.UTF_8);
                String text = new String(in.readNBytes(Integer.parseInt(sizes[1])), StandardCharsets.UTF_8);
                String name = path.substring(path.lastIndexOf('/') + 1);
                Json json = new Json();
                try {
                    if (name.endsWith(".java")) {
                        new JavaFile(json, (PsiJavaFile) java.createFileFromText(name, JavaLanguage.INSTANCE, text)).write();
                    } else {
                        new KotlinFile(json, kotlin.createFile(name, text)).write();
                    }
                } catch (RuntimeException failure) {
                    json = new Json();
                    StackTraceElement[] stack = failure.getStackTrace();
                    json.open('{').key("crash").value(failure + (stack.length > 0 ? " at " + stack[0] : "")).close('}');
                }
                out.write(json.text().getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                out.flush();
            }
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) != '\n') {
            if (next < 0) return bytes.size() == 0 ? null : bytes.toString(StandardCharsets.UTF_8);
            bytes.write(next);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** JSON written by hand: the compiler's jars are the only libraries the guard takes. */
    static final class Json {
        private final StringBuilder text = new StringBuilder();
        private boolean first = true;

        String text() { return text.toString(); }

        Json open(char bracket) { comma(); text.append(bracket); first = true; return this; }

        Json close(char bracket) { text.append(bracket); first = false; return this; }

        Json key(String key) { comma(); string(key); text.append(':'); first = true; return this; }

        Json value(String value) {
            comma();
            if (value == null) text.append("null"); else string(value);
            first = false;
            return this;
        }

        Json value(long value) { comma(); text.append(value); first = false; return this; }

        Json value(boolean value) { comma(); text.append(value); first = false; return this; }

        Json strings(List<String> values) {
            if (values == null) return value((String) null);
            open('[');
            for (String value : values) value(value);
            return close(']');
        }

        Json ints(List<Integer> values) {
            open('[');
            for (int value : values) value(value);
            return close(']');
        }

        private void comma() { if (!first) text.append(','); first = false; }

        private void string(String value) {
            text.append('"');
            for (int at = 0; at < value.length(); at++) {
                char c = value.charAt(at);
                switch (c) {
                    case '"' -> text.append("\\\"");
                    case '\\' -> text.append("\\\\");
                    case '\n' -> text.append("\\n");
                    case '\r' -> text.append("\\r");
                    case '\t' -> text.append("\\t");
                    default -> {
                        // Control characters and Unicode's own line ends are escaped: an answer is one line.
                        if (c < 0x20 || c >= 0x7f && c <= 0x9f || c == 0x2028 || c == 0x2029) text.append(String.format("\\u%04x", (int) c));
                        else text.append(c);
                    }
                }
            }
            text.append('"');
        }
    }

    /** A declaration the guard can select, or that only binds names (a local, a parameter). */
    static final class Decl {
        final int id;
        final int parent;
        final String kind;
        final String name;
        final int line;
        final List<String> flags = new ArrayList<>();
        String value;
        // How many arguments a call can pass, for each of its signatures: {least, most}, most -1 for any. Null when not callable.
        List<int[]> arity;

        Decl(int id, int parent, String kind, String name, int line) {
            this.id = id;
            this.parent = parent;
            this.kind = kind;
            this.name = name;
            this.line = line;
        }
    }

    /** What a name in a scope is, and what a use of it needs to be sure it is this one. */
    static final class Binding {
        final int decl;
        final String what;
        final int from;
        boolean extension;
        boolean operator;
        boolean instance;
        int required = -1;
        boolean invokableWithoutArguments;

        Binding(int decl, String what, int from) {
            this.decl = decl;
            this.what = what;
            this.from = from;
        }
    }

    static final class Scope {
        final String kind;
        final boolean staticBoundary;
        final Map<String, List<Binding>> names = new HashMap<>();

        Scope(String kind, boolean staticBoundary) {
            this.kind = kind;
            this.staticBoundary = staticBoundary;
        }

        void add(String name, Binding binding) {
            if (name != null && !name.isEmpty()) names.computeIfAbsent(name, key -> new ArrayList<>()).add(binding);
        }
    }

    /** Code that reaches a class, a member or a resource by its name, or native code: kind says which. ref is a use that names it. */
    record Reach(int owner, int line, String kind, String name, List<String> chain, int ref, String text) {}

    /** A use of a name. form says how it is used; chain is the dotted names it starts, for a receiver or a type. */
    static final class Ref {
        String name;
        String form;
        int owner;
        int line;
        boolean called;
        int certain;
        List<Integer> binds = new ArrayList<>();
        List<String> chain;
        // The arguments a call passes, -1 when it is no call or a spread hides how many.
        int arguments = -1;
    }

    /** What both languages share: declarations, uses, reflection, resources and the scopes that bind names. */
    abstract static class SourceFile {
        final Json json;
        final PsiFile file;
        final List<Decl> decls = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();
        final List<Reach> reflections = new ArrayList<>();
        final List<Reach> resources = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Deque<Scope> scopes = new ArrayDeque<>();
        final Deque<Integer> owners = new ArrayDeque<>();
        final Map<PsiElement, Integer> refAt = new IdentityHashMap<>();
        final List<String[]> imports = new ArrayList<>();
        String packageName = "";

        SourceFile(Json json, PsiFile file) {
            this.json = json;
            this.file = file;
            owners.push(-1);
        }

        private int[] lineStarts;

        int line(PsiElement element) {
            if (lineStarts == null) {
                String text = file.getText();
                List<Integer> starts = new ArrayList<>(List.of(0));
                for (int at = 0; at < text.length(); at++) if (text.charAt(at) == '\n') starts.add(at + 1);
                lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
            }
            int found = java.util.Arrays.binarySearch(lineStarts, element.getTextRange().getStartOffset());
            return found >= 0 ? found + 1 : -found - 1;
        }

        Decl declare(String kind, String name, PsiElement element) {
            Decl decl = new Decl(decls.size(), owners.peek(), kind, name, line(element));
            decls.add(decl);
            return decl;
        }

        int owner() { return owners.peek(); }

        Ref ref(PsiElement at, String name, String form) {
            Ref ref = new Ref();
            ref.name = name;
            ref.form = form;
            ref.owner = owner();
            ref.line = line(at);
            refAt.put(at, refs.size());
            refs.add(ref);
            return ref;
        }

        /** The file's own bindings of a name, innermost first, and whether one of them surely is the one. */
        void bind(Ref ref, int offset, int arguments) {
            boolean staticOnly = false;
            for (Scope scope : scopes) {
                boolean sure = false;
                for (Binding binding : scope.names.getOrDefault(ref.name, List.of())) {
                    if (binding.from > offset || !accepts(ref.form, binding)) continue;
                    if (binding.decl >= 0) ref.binds.add(binding.decl);
                    if (scope.kind.equals("file") || staticOnly && binding.instance) continue;
                    int certainty = certainty(ref.form, binding, arguments);
                    if (certainty > ref.certain) ref.certain = certainty;
                    sure |= certainty > 0;
                }
                if (sure) return;
                if (scope.staticBoundary) staticOnly = true;
            }
        }

        static boolean accepts(String form, Binding binding) {
            String what = binding.what;
            boolean value = what.equals("property") || what.equals("local") || what.equals("object") || what.equals("class");
            return switch (form) {
                case "name" -> value;
                case "recv" -> value || what.equals("typealias");
                case "type" -> what.equals("class") || what.equals("object") || what.equals("typealias") || what.equals("typeparameter");
                case "call", "callable" -> !what.equals("typeparameter");
                default -> false;
            };
        }

        /** 0: it may still be another declaration. 1: it is surely this one. 2: a value surely is, a class may still be. */
        static int certainty(String form, Binding binding, int arguments) {
            String what = binding.what;
            switch (form) {
                case "name":
                case "type":
                    return 1;
                case "recv":
                    return what.equals("property") || what.equals("local") ? 2 : 1;
                case "call":
                    if (arguments != 0) return 0;
                    if (what.equals("function")) return binding.required == 0 && !binding.extension ? 1 : 0;
                    if (what.equals("class")) return binding.required == 0 ? 1 : 0;
                    if (what.equals("property") || what.equals("local")) return binding.invokableWithoutArguments ? 1 : 0;
                    return 0;
                default:
                    return 0;
            }
        }

        /** Expand the first name through explicit imports, including Kotlin aliases and Java static imports. */
        List<String> importedChain(List<String> chain) {
            if (chain == null || chain.isEmpty()) return chain;
            for (String[] imported : imports) {
                if (imported[2] != null && imported[2].endsWith("*")) continue;
                List<String> target = new ArrayList<>(List.of(imported[0].split("\\.")));
                String local = imported[1] == null ? target.get(target.size() - 1) : imported[1];
                if (!local.equals(chain.get(0))) continue;
                target.addAll(chain.subList(1, chain.size()));
                return target;
            }
            // Static wildcard imports can name these loaders without their declaring class.
            for (String[] imported : imports) {
                if (imported[2] == null || !imported[2].endsWith("*")) continue;
                String target = imported[0];
                String name = chain.get(0);
                if (target.equals("java.lang.Runtime") && name.equals("getRuntime")
                    || chain.size() == 1 && (target.equals("java.util.ServiceLoader") && (name.equals("load") || name.equals("loadInstalled"))
                        || target.equals("java.lang.System") && NATIVE_LOADS.contains(name))) {
                    List<String> found = new ArrayList<>(List.of(target.split("\\.")));
                    found.addAll(chain);
                    return found;
                }
            }
            return chain;
        }

        List<String> callNames(String name, List<String> receiver) {
            // An explicit receiver that cannot be named is not a bare imported function.
            if (receiver != null && receiver.isEmpty()) return List.of(name);
            List<String> chain = new ArrayList<>(receiver == null ? List.of() : receiver);
            chain.add(name);
            return importedChain(chain);
        }

        /** The same native-loader rules for calls and callable references in both languages. */
        boolean nativeLoad(List<String> call) {
            String name = call.get(call.size() - 1);
            if (!NATIVE_LOADS.contains(name)) return false;
            if (name.equals("loadLibrary")) return true;
            String receiver = String.join(".", call.subList(0, call.size() - 1));
            return Set.of("System", "java.lang.System", "Runtime", "java.lang.Runtime", "Runtime.getRuntime", "java.lang.Runtime.getRuntime").contains(receiver);
        }

        String importedName(String name) {
            List<String> chain = importedChain(List.of(name));
            return chain.get(chain.size() - 1);
        }

        void reflection(PsiElement at, String kind, String name, List<String> chain, String text) {
            reflections.add(new Reach(owner(), line(at), kind, name, chain, -1, text));
        }

        void resource(PsiElement at, String name, Integer ref, String text) {
            resources.add(new Reach(owner(), line(at), "resource", name, null, ref == null ? -1 : ref, text));
        }

        void errors() {
            for (PsiErrorElement error : PsiTreeUtil.findChildrenOfType(file, PsiErrorElement.class)) {
                errors.add(line(error) + ": " + error.getErrorDescription());
            }
        }

        abstract String language();

        abstract String facade();

        void write() {
            json.open('{');
            json.key("language").value(language());
            json.key("package").value(packageName);
            json.key("facade").value(facade());
            json.key("errors").strings(errors);
            json.key("imports").open('[');
            for (String[] row : imports) json.open('[').value(row[0]).value(row[1]).value(row[2]).close(']');
            json.close(']');
            json.key("decls").open('[');
            for (Decl decl : decls) {
                json.open('{').key("id").value(decl.id).key("parent").value(decl.parent).key("kind").value(decl.kind).key("name").value(decl.name)
                    .key("line").value(decl.line).key("flags").strings(decl.flags).key("value").value(decl.value);
                json.key("arity");
                if (decl.arity == null) json.value((String) null);
                else {
                    json.open('[');
                    for (int[] range : decl.arity) json.open('[').value(range[0]).value(range[1]).close(']');
                    json.close(']');
                }
                json.close('}');
            }
            json.close(']');
            json.key("refs").open('[');
            for (Ref ref : refs) {
                json.open('{').key("n").value(ref.name).key("f").value(ref.form).key("o").value(ref.owner).key("l").value(ref.line)
                    .key("call").value(ref.called).key("a").value(ref.arguments).key("c").value(ref.certain).key("b").ints(ref.binds).key("q").strings(ref.chain).close('}');
            }
            json.close(']');
            for (String key : List.of("reflections", "resources")) {
                json.key(key).open('[');
                for (Reach row : key.equals("reflections") ? reflections : resources) {
                    json.open('{').key("o").value(row.owner()).key("l").value(row.line()).key("kind").value(row.kind()).key("name").value(row.name())
                        .key("chain").strings(row.chain()).key("ref").value(row.ref())
                        .key("text").value(row.text().length() > 160 ? row.text().substring(0, 160) : row.text()).close('}');
                }
                json.close(']');
            }
            json.close('}');
        }
    }

    // Names that load a class by its name, and calls that reach a class's members by name.
    static final Set<String> CLASS_LOADS = Set.of("forName", "loadClass", "findClass", "findSystemClass", "findLoadedClass", "findBootstrapClassOrNull");
    static final Set<String> MEMBER_LOOKUPS = Set.of("getMethod", "getDeclaredMethod", "getField", "getDeclaredField", "getConstructor",
        "getDeclaredConstructor", "getMethods", "getDeclaredMethods", "getFields", "getDeclaredFields", "getConstructors", "getDeclaredConstructors",
        "getRecordComponents", "findStatic", "findVirtual", "findSpecial", "findConstructor", "findGetter", "findSetter", "findStaticGetter",
        "findStaticSetter", "findVarHandle", "findStaticVarHandle", "memberFunctions", "declaredMemberFunctions", "memberProperties",
        "declaredMemberProperties", "declaredFunctions", "staticFunctions", "staticProperties", "declaredMembers", "memberExtensionFunctions",
        "memberExtensionProperties", "declaredMemberExtensionFunctions", "declaredMemberExtensionProperties", "primaryConstructor", "functions", "members");
    static final Set<String> RESOURCE_LOADS = Set.of("getResource", "getResourceAsStream", "getSystemResource", "getSystemResourceAsStream", "getResources", "getSystemResources");
    // Native code: System.load, Runtime's load, and loadLibrary anywhere. A native method itself is a decl flagged native.
    static final Set<String> NATIVE_LOADS = Set.of("loadLibrary", "load");

    static final class KotlinFile extends SourceFile {
        private final KtFile kt;
        private String jvmName;

        KotlinFile(Json json, KtFile file) {
            super(json, file);
            this.kt = file;
        }

        @Override String language() { return "kotlin"; }

        @Override String facade() {
            if (jvmName != null) return jvmName;
            String name = kt.getName();
            String base = name.endsWith(".kt") ? name.substring(0, name.length() - 3) : name;
            return Character.toUpperCase(base.charAt(0)) + base.substring(1) + "Kt";
        }

        @Override void write() {
            errors();
            packageName = kt.getPackageFqName().asString();
            for (KtImportDirective directive : kt.getImportDirectives()) {
                if (directive.getImportedFqName() == null) continue;
                imports.add(new String[]{directive.getImportedFqName().asString(), directive.getAliasName(), directive.isAllUnder() ? "*" : null});
            }
            for (KtAnnotationEntry entry : kt.getFileAnnotationList() == null ? List.<KtAnnotationEntry>of() : kt.getFileAnnotationList().getAnnotationEntries()) {
                if (entry.getShortName() != null && entry.getShortName().asString().equals("JvmName") && !entry.getValueArguments().isEmpty()) {
                    jvmName = literal(entry.getValueArguments().get(0).getArgumentExpression());
                }
            }
            Scope top = new Scope("file", false);
            scopes.push(top);
            List<KtDeclaration> declarations = kt.getDeclarations();
            Map<KtDeclaration, Integer> ids = members(top, declarations, false);
            if (kt.getFileAnnotationList() != null) visit(kt.getFileAnnotationList());
            for (KtDeclaration declaration : declarations) member(declaration, ids, null);
            scopes.pop();
            super.write();
        }

        /** Declares a body's members up front: a class's and a file's names are seen before their place. */
        private Map<KtDeclaration, Integer> members(Scope scope, List<KtDeclaration> declarations, boolean instance) {
            Map<KtDeclaration, Integer> ids = new IdentityHashMap<>();
            for (KtDeclaration declaration : declarations) {
                Decl decl = register(declaration);
                if (decl == null) continue;
                ids.put(declaration, decl.id);
                Binding binding = binding(declaration, decl.id, 0);
                if (binding == null) continue;
                binding.instance = instance && !(declaration instanceof KtClassOrObject) && !(declaration instanceof KtTypeAlias);
                scope.add(decl.name, binding);
                if (declaration instanceof KtObjectDeclaration object && object.isCompanion()) {
                    // A companion's members are names in its class, from the class and from its nested classes.
                    owners.push(decl.id);
                    Map<KtDeclaration, Integer> inner = members(new Scope("companion", true), object.getDeclarations(), false);
                    owners.pop();
                    for (KtDeclaration member : object.getDeclarations()) {
                        Integer id = inner.get(member);
                        Binding shared = id == null ? null : binding(member, id, 0);
                        if (shared != null) scope.add(decls.get(id).name, shared);
                    }
                    companions.put(object, inner);
                }
            }
            return ids;
        }

        private final Map<KtObjectDeclaration, Map<KtDeclaration, Integer>> companions = new IdentityHashMap<>();

        private Decl register(KtDeclaration declaration) {
            String kind;
            if (declaration instanceof KtEnumEntry) kind = "enumentry";
            else if (declaration instanceof KtObjectDeclaration object) kind = object.isCompanion() ? "companion" : "object";
            else if (declaration instanceof KtClass type) kind = type.isInterface() ? "interface" : "class";
            else if (declaration instanceof KtNamedFunction) kind = "function";
            else if (declaration instanceof KtProperty) kind = "property";
            else if (declaration instanceof KtTypeAlias) kind = "typealias";
            else if (declaration instanceof KtAnonymousInitializer) kind = "init";
            else if (declaration instanceof KtSecondaryConstructor) kind = "constructor";
            else if (declaration instanceof KtDestructuringDeclaration) kind = "destructuring";
            else kind = "other";
            String name = declaration instanceof KtObjectDeclaration object && object.isCompanion() && object.getName() == null ? "Companion" : declaration.getName();
            Decl decl = declare(kind, name == null ? "" : name, declaration);
            flags(decl, declaration);
            if (declaration instanceof KtProperty property) {
                decl.value = literal(property.getInitializer());
                if (property.getInitializer() != null || property.getDelegate() != null) decl.flags.add("eager");
            }
            decl.arity = arity(declaration);
            return decl;
        }

        private void flags(Decl decl, KtDeclaration declaration) {
            if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) decl.flags.add("private");
            if (declaration.hasModifier(KtTokens.OPERATOR_KEYWORD)) decl.flags.add("operator");
            if (declaration.hasModifier(KtTokens.INFIX_KEYWORD)) decl.flags.add("infix");
            if (declaration.hasModifier(KtTokens.EXTERNAL_KEYWORD)) decl.flags.add("native");
            if (declaration.hasModifier(KtTokens.INNER_KEYWORD)) decl.flags.add("inner");
            if (declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD)) decl.flags.add("override");
            if (declaration instanceof KtCallableDeclaration callable && callable.getReceiverTypeReference() != null) decl.flags.add("extension");
            if (declaration instanceof KtProperty property && receiverFunctionType(property.getTypeReference())) decl.flags.add("extension");
            if (declaration instanceof KtClass type && type.isEnum()) decl.flags.add("enum");
            if (declaration instanceof KtClassOrObject type && !type.getSuperTypeListEntries().isEmpty()) decl.flags.add("supertypes");
        }

        private static boolean receiverFunctionType(KtTypeReference type) {
            if (type == null) return false;
            KtTypeElement element = type.getTypeElement();
            while (element instanceof KtNullableType nullable) element = nullable.getInnerType();
            return element instanceof KtFunctionType function && function.getReceiver() != null;
        }

        private Binding binding(KtDeclaration declaration, int decl, int from) {
            Binding binding;
            if (declaration instanceof KtNamedFunction function) {
                binding = new Binding(decl, "function", from);
                binding.extension = function.getReceiverTypeReference() != null;
                binding.operator = function.hasModifier(KtTokens.OPERATOR_KEYWORD);
                binding.required = required(function.getValueParameters());
            } else if (declaration instanceof KtProperty property) {
                binding = new Binding(decl, property.isLocal() ? "local" : "property", from);
                binding.extension = property.getReceiverTypeReference() != null || receiverFunctionType(property.getTypeReference());
                binding.invokableWithoutArguments = invokableWithoutArguments(property.getTypeReference(), property.getInitializer());
            } else if (declaration instanceof KtEnumEntry) {
                binding = new Binding(decl, "property", from);
            } else if (declaration instanceof KtObjectDeclaration) {
                binding = new Binding(decl, "object", from);
            } else if (declaration instanceof KtClass type) {
                binding = new Binding(decl, "class", from);
                binding.required = type.isInterface() || type.hasModifier(KtTokens.ABSTRACT_KEYWORD) || type.hasModifier(KtTokens.SEALED_KEYWORD) ? -1
                    : type.getPrimaryConstructor() == null ? (type.getSecondaryConstructors().isEmpty() ? 0 : -1) : required(type.getPrimaryConstructorParameters());
            } else if (declaration instanceof KtTypeAlias) {
                binding = new Binding(decl, "typealias", from);
            } else {
                return null;
            }
            return binding;
        }

        /** The argument counts a call to [declaration] can pass: a function's, or a class's constructors'. */
        private static List<int[]> arity(KtDeclaration declaration) {
            if (declaration instanceof KtNamedFunction function) return List.of(range(function.getValueParameters()));
            if (!(declaration instanceof KtClass type) || type.isInterface() || type.hasModifier(KtTokens.ABSTRACT_KEYWORD)
                || type.hasModifier(KtTokens.SEALED_KEYWORD) || type.isEnum()) return null;
            List<int[]> ranges = new ArrayList<>();
            if (type.getPrimaryConstructor() != null) ranges.add(range(type.getPrimaryConstructorParameters()));
            for (KtSecondaryConstructor secondary : type.getSecondaryConstructors()) ranges.add(range(secondary.getValueParameters()));
            if (ranges.isEmpty()) ranges.add(new int[]{0, 0});
            return ranges;
        }

        private static int[] range(List<KtParameter> parameters) {
            boolean any = parameters.stream().anyMatch(KtParameter::isVarArg);
            return new int[]{required(parameters), any ? -1 : parameters.size()};
        }

        private static int required(List<KtParameter> parameters) {
            int count = 0;
            for (KtParameter parameter : parameters) if (!parameter.hasDefaultValue() && !parameter.isVarArg()) count++;
            return count;
        }

        /** A function type or a lambda with no parameters: a call with no arguments surely invokes it. */
        private static boolean invokableWithoutArguments(KtTypeReference type, KtExpression initializer) {
            if (type != null) {
                return type.getTypeElement() instanceof KtFunctionType function && function.getReceiver() == null && function.getParameters().isEmpty()
                    && function.getContextReceiversTypeReferences().isEmpty() && function.getContextParameterList() == null;
            }
            KtExpression value = bare(initializer);
            if (value instanceof KtLambdaExpression lambda) {
                return lambda.getFunctionLiteral().hasParameterSpecification() && lambda.getValueParameters().isEmpty()
                    || !lambda.getFunctionLiteral().hasParameterSpecification() && !usesIt(lambda);
            }
            return value instanceof KtNamedFunction function && function.getValueParameters().isEmpty() && function.getReceiverTypeReference() == null;
        }

        private static boolean usesIt(KtLambdaExpression lambda) {
            for (KtNameReferenceExpression name : PsiTreeUtil.findChildrenOfType(lambda.getBodyExpression(), KtNameReferenceExpression.class)) {
                if (name.getReferencedName().equals("it")) return true;
            }
            return false;
        }

        /** A member of a file or class body, whose decl id was given when its body was declared. */
        private void member(KtDeclaration declaration, Map<KtDeclaration, Integer> ids, Scope constructor) {
            Integer id = ids.get(declaration);
            if (id == null) {
                visit(declaration);
                return;
            }
            owners.push(id);
            if (declaration instanceof KtClassOrObject type) {
                classOrObject(type, id);
            } else if (declaration instanceof KtNamedFunction function) {
                function(function);
            } else if (declaration instanceof KtProperty property) {
                if (constructor != null) scopes.push(constructor);
                property(property);
                if (constructor != null) scopes.pop();
            } else if (declaration instanceof KtAnonymousInitializer initializer) {
                if (constructor != null) scopes.push(constructor);
                visitChildren(initializer);
                if (constructor != null) scopes.pop();
            } else if (declaration instanceof KtSecondaryConstructor secondary) {
                scopes.push(new Scope("function", false));
                parameters(secondary.getValueParameters());
                if (secondary.getModifierList() != null) visit(secondary.getModifierList());
                if (secondary.getDelegationCall() != null) visit(secondary.getDelegationCall());
                if (secondary.getBodyExpression() != null) visit(secondary.getBodyExpression());
                scopes.pop();
            } else {
                visitChildren(declaration);
            }
            owners.pop();
        }

        private void classOrObject(KtClassOrObject type, int id) {
            boolean nested = type.getParent() instanceof KtClassBody && !type.hasModifier(KtTokens.INNER_KEYWORD);
            scopes.push(new Scope("typeparameters", false));
            if (type instanceof KtClass klass) typeParameters(klass.getTypeParameters());
            if (type.getModifierList() != null) visit(type.getModifierList());
            Scope constructor = new Scope("constructor", false);
            scopes.push(constructor);
            if (type.getPrimaryConstructor() != null) {
                // The constructor's own uses: a class reached only through its companion never runs them.
                KtPrimaryConstructor primary = type.getPrimaryConstructor();
                Decl decl = declare("constructor", "<init>", primary);
                owners.push(decl.id);
                if (primary.getModifierList() != null) visit(primary.getModifierList());
                parameters(primary.getValueParameters());
                owners.pop();
            }
            scopes.pop();
            boolean object = type instanceof KtObjectDeclaration;
            Scope body = new Scope("class", nested || object && !((KtObjectDeclaration) type).isObjectLiteral() && type.getParent() instanceof KtClassBody);
            // Constructor properties are the class's members too.
            if (type.getPrimaryConstructor() != null) {
                for (KtParameter parameter : type.getPrimaryConstructorParameters()) {
                    if (!parameter.hasValOrVar()) continue;
                    owners.push(id);
                    Decl decl = declare("property", parameter.getName() == null ? "" : parameter.getName(), parameter);
                    owners.pop();
                    if (parameter.hasModifier(KtTokens.PRIVATE_KEYWORD)) decl.flags.add("private");
                    Binding binding = new Binding(decl.id, "property", 0);
                    binding.instance = !object;
                    binding.invokableWithoutArguments = invokableWithoutArguments(parameter.getTypeReference(), null);
                    body.add(decl.name, binding);
                }
            }
            List<KtDeclaration> declarations = type.getDeclarations();
            owners.push(id);
            Map<KtDeclaration, Integer> ids = members(body, declarations, !object);
            owners.pop();
            scopes.push(body);
            scopes.push(constructor);
            for (KtSuperTypeListEntry entry : type.getSuperTypeListEntries()) visit(entry);
            scopes.pop();
            if (type instanceof KtEnumEntry entry && entry.getInitializerList() != null) visit(entry.getInitializerList());
            for (KtDeclaration declaration : declarations) {
                if (declaration instanceof KtObjectDeclaration companion && companion.isCompanion() && companions.containsKey(companion)) {
                    owners.push(ids.get(declaration));
                    companion(companion, ids.get(declaration));
                    owners.pop();
                } else {
                    member(declaration, ids, constructor);
                }
            }
            scopes.pop();
            scopes.pop();
        }

        private void companion(KtObjectDeclaration companion, int id) {
            Map<KtDeclaration, Integer> ids = companions.get(companion);
            if (companion.getModifierList() != null) visit(companion.getModifierList());
            Scope body = new Scope("class", true);
            for (KtDeclaration declaration : companion.getDeclarations()) {
                Integer member = ids.get(declaration);
                Binding binding = member == null ? null : binding(declaration, member, 0);
                if (binding != null) body.add(decls.get(member).name, binding);
            }
            scopes.push(body);
            for (KtSuperTypeListEntry entry : companion.getSuperTypeListEntries()) visit(entry);
            for (KtDeclaration declaration : companion.getDeclarations()) member(declaration, ids, null);
            scopes.pop();
        }

        private void typeParameters(List<KtTypeParameter> parameters) {
            for (KtTypeParameter parameter : parameters) scopes.peek().add(parameter.getName(), new Binding(-1, "typeparameter", 0));
            for (KtTypeParameter parameter : parameters) if (parameter.getExtendsBound() != null) visit(parameter.getExtendsBound());
        }

        /** Parameters, each seen by the defaults after it. */
        private void parameters(List<KtParameter> parameters) {
            for (KtParameter parameter : parameters) {
                if (parameter.getModifierList() != null) visit(parameter.getModifierList());
                if (parameter.getTypeReference() != null) visit(parameter.getTypeReference());
                if (parameter.getDefaultValue() != null) visit(parameter.getDefaultValue());
                local(parameter, parameter.getTypeReference(), parameter.getTextRange().getStartOffset());
            }
        }

        private void local(KtParameter parameter, KtTypeReference type, int from) {
            if (parameter.getDestructuringDeclaration() != null) {
                destructure(parameter.getDestructuringDeclaration(), from);
                return;
            }
            Binding binding = new Binding(-1, "local", from);
            binding.invokableWithoutArguments = invokableWithoutArguments(type, null);
            scopes.peek().add(parameter.getName(), binding);
        }

        private void destructure(KtDestructuringDeclaration destructuring, int from) {
            List<KtDestructuringDeclarationEntry> entries = destructuring.getEntries();
            for (int index = 0; index < entries.size(); index++) {
                ref(destructuring, "component" + (index + 1), "op");
                scopes.peek().add(entries.get(index).getName(), new Binding(-1, "local", from));
            }
        }

        private void function(KtNamedFunction function) {
            scopes.push(new Scope("function", false));
            typeParameters(function.getTypeParameters());
            if (function.getModifierList() != null) visit(function.getModifierList());
            if (function.getReceiverTypeReference() != null) visit(function.getReceiverTypeReference());
            for (KtParameter parameter : function.getContextParameters()) local(parameter, parameter.getTypeReference(), 0);
            if (function.getTypeReference() != null) visit(function.getTypeReference());
            if (function.getTypeConstraintList() != null) visit(function.getTypeConstraintList());
            parameters(function.getValueParameters());
            if (function.getBodyExpression() != null) visit(function.getBodyExpression());
            scopes.pop();
        }

        private void property(KtProperty property) {
            scopes.push(new Scope("function", false));
            typeParameters(property.getTypeParameters());
            if (property.getModifierList() != null) visit(property.getModifierList());
            if (property.getReceiverTypeReference() != null) visit(property.getReceiverTypeReference());
            if (property.getTypeReference() != null) visit(property.getTypeReference());
            if (property.getTypeConstraintList() != null) visit(property.getTypeConstraintList());
            if (property.getInitializer() != null) visit(property.getInitializer());
            if (property.getDelegate() != null) {
                for (String name : List.of("getValue", "setValue", "provideDelegate")) ref(property.getDelegate(), name, "op");
                visit(property.getDelegate());
            }
            for (KtPropertyAccessor accessor : property.getAccessors()) {
                scopes.push(new Scope("function", false));
                scopes.peek().add("field", new Binding(-1, "local", 0));
                if (accessor.getModifierList() != null) visit(accessor.getModifierList());
                parameters(accessor.getValueParameters());
                if (accessor.getTypeReference() != null) visit(accessor.getTypeReference());
                if (accessor.getBodyExpression() != null) visit(accessor.getBodyExpression());
                scopes.pop();
            }
            scopes.pop();
        }

        private void visitChildren(PsiElement element) {
            for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) visit(child);
        }

        private void visit(PsiElement element) {
            if (element == null || element instanceof PsiWhiteSpace || element instanceof PsiComment || element instanceof KDoc) return;
            if (element instanceof KtPackageDirective || element instanceof KtImportList) return;
            if (element instanceof KtValueArgumentName || element instanceof KtLabelReferenceExpression) return;
            if (element instanceof KtBlockExpression block) {
                scopes.push(new Scope("block", false));
                for (KtExpression statement : block.getStatements()) statement(statement);
                scopes.pop();
            } else if (element instanceof KtDeclaration declaration && isLocal(declaration)) {
                statement(declaration);
            } else if (element instanceof KtLambdaExpression lambda) {
                lambda(lambda);
            } else if (element instanceof KtObjectLiteralExpression literal) {
                KtObjectDeclaration object = literal.getObjectDeclaration();
                Decl decl = declare("object", "", object);
                owners.push(decl.id);
                classOrObject(object, decl.id);
                owners.pop();
            } else if (element instanceof KtForExpression loop) {
                if (loop.getLoopRange() != null) visit(loop.getLoopRange());
                for (String name : List.of("iterator", "hasNext", "next")) ref(loop, name, "op");
                scopes.push(new Scope("block", false));
                if (loop.getLoopParameter() != null) {
                    if (loop.getLoopParameter().getTypeReference() != null) visit(loop.getLoopParameter().getTypeReference());
                    local(loop.getLoopParameter(), loop.getLoopParameter().getTypeReference(), 0);
                }
                if (loop.getDestructuringDeclaration() != null) destructure(loop.getDestructuringDeclaration(), 0);
                if (loop.getBody() != null) visit(loop.getBody());
                scopes.pop();
            } else if (element instanceof KtCatchClause clause) {
                scopes.push(new Scope("block", false));
                if (clause.getCatchParameter() != null) {
                    visit(clause.getCatchParameter().getTypeReference());
                    local(clause.getCatchParameter(), null, 0);
                }
                if (clause.getCatchBody() != null) visit(clause.getCatchBody());
                scopes.pop();
            } else if (element instanceof KtWhenExpression when && when.getSubjectVariable() != null) {
                scopes.push(new Scope("block", false));
                statement(when.getSubjectVariable());
                for (KtWhenEntry entry : when.getEntries()) visit(entry);
                scopes.pop();
            } else if (element instanceof KtUserType type) {
                userType(type);
            } else if (element instanceof KtNameReferenceExpression name) {
                name(name);
            } else if (element instanceof KtCallExpression call) {
                call(call);
            } else if (element instanceof KtOperationReferenceExpression operation) {
                operation(operation);
            } else if (element instanceof KtArrayAccessExpression access) {
                ref(access, "get", "op");
                ref(access, "set", "op");
                visitChildren(access);
            } else if (element instanceof KtWhenConditionInRange range) {
                ref(range, "contains", "op");
                visitChildren(range);
            } else if (element instanceof KtClassLiteralExpression literal) {
                List<String> chain = chainOf(literal.getReceiverExpression());
                if (chain != null) typeRef(literal.getReceiverExpression(), chain); else visitChildren(literal);
            } else if (element instanceof KtCallableReferenceExpression reference) {
                callable(reference);
            } else if (element instanceof KtThisExpression || element instanceof KtSuperExpression) {
                if (element instanceof KtSuperExpression superExpression && superExpression.getSuperTypeQualifier() != null) visit(superExpression.getSuperTypeQualifier());
            } else if (element instanceof KtDotQualifiedExpression || element instanceof KtSafeQualifiedExpression) {
                KtQualifiedExpression qualified = (KtQualifiedExpression) element;
                visitChildren(qualified);
                memberReflection(qualified);
            } else {
                visitChildren(element);
            }
        }

        private static boolean isLocal(KtDeclaration declaration) {
            PsiElement parent = declaration.getParent();
            return parent instanceof KtBlockExpression || declaration instanceof KtNamedFunction function && function.isLocal() && !(parent instanceof KtClassBody) && !(parent instanceof KtFile);
        }

        /** A statement of a block: a local is seen after its declaration, a local function or class inside it too. */
        private void statement(KtElement statement) {
            int start = statement.getTextRange().getStartOffset();
            int end = statement.getTextRange().getEndOffset();
            if (statement instanceof KtProperty property) {
                property(property);
                Binding binding = binding(property, -1, end);
                scopes.peek().add(property.getName(), binding);
            } else if (statement instanceof KtDestructuringDeclaration destructuring) {
                if (destructuring.getInitializer() != null) visit(destructuring.getInitializer());
                destructure(destructuring, end);
            } else if (statement instanceof KtNamedFunction function && function.getName() != null) {
                scopes.peek().add(function.getName(), binding(function, -1, start));
                function(function);
            } else if (statement instanceof KtClassOrObject type && type.getName() != null) {
                Decl decl = register(type);
                Binding binding = binding(type, -1, start);
                scopes.peek().add(type.getName(), binding);
                owners.push(decl.id);
                classOrObject(type, decl.id);
                owners.pop();
            } else if (statement instanceof KtTypeAlias alias) {
                scopes.peek().add(alias.getName(), new Binding(-1, "typealias", start));
                visitChildren(alias);
            } else if (statement instanceof KtNamedFunction function) {
                function(function);
            } else {
                visit(statement);
            }
        }

        private void lambda(KtLambdaExpression lambda) {
            KtFunctionLiteral literal = lambda.getFunctionLiteral();
            scopes.push(new Scope("function", false));
            if (literal.hasParameterSpecification()) {
                for (KtParameter parameter : literal.getValueParameters()) {
                    if (parameter.getTypeReference() != null) visit(parameter.getTypeReference());
                    local(parameter, parameter.getTypeReference(), 0);
                }
            } else {
                scopes.peek().add("it", new Binding(-1, "local", 0));
            }
            if (literal.getBodyExpression() != null) visit(literal.getBodyExpression());
            scopes.pop();
        }

        private void userType(KtUserType type) {
            List<String> chain = new ArrayList<>();
            KtUserType first = type;
            for (KtUserType part = type; part != null; part = part.getQualifier()) {
                chain.add(0, part.getReferencedName());
                first = part;
            }
            if (!chain.contains(null) && first.getReferenceExpression() != null) {
                Ref ref = ref(first.getReferenceExpression(), chain.get(0), "type");
                ref.chain = chain;
                bind(ref, first.getTextRange().getStartOffset(), -1);
            }
            for (KtUserType part = type; part != null; part = part.getQualifier()) {
                if (part.getTypeArgumentList() != null) visit(part.getTypeArgumentList());
            }
        }

        private void typeRef(KtExpression at, List<String> chain) {
            Ref ref = ref(leftmost(at), chain.get(0), "type");
            ref.chain = chain;
            bind(ref, at.getTextRange().getStartOffset(), -1);
        }

        private static PsiElement leftmost(KtExpression expression) {
            KtExpression at = expression;
            while (at instanceof KtQualifiedExpression qualified) at = qualified.getReceiverExpression();
            return at;
        }

        /** Dotted names only, as a package, a class or a value with members: null for any other expression. */
        private static List<String> chainOf(KtExpression expression) {
            if (expression instanceof KtNameReferenceExpression name) return new ArrayList<>(List.of(name.getReferencedName()));
            if (expression instanceof KtDotQualifiedExpression qualified && qualified.getSelectorExpression() instanceof KtNameReferenceExpression name) {
                List<String> chain = chainOf(qualified.getReceiverExpression());
                if (chain != null) chain.add(name.getReferencedName());
                return chain;
            }
            return null;
        }

        /** Names through a receiver's calls, so Runtime.getRuntime() and imported getRuntime() stay identifiable. */
        private static List<String> receiverPath(KtExpression expression) {
            KtExpression value = bare(expression);
            if (value instanceof KtNameReferenceExpression name) return new ArrayList<>(List.of(name.getReferencedName()));
            if (value instanceof KtCallExpression call && call.getCalleeExpression() instanceof KtNameReferenceExpression name) {
                return new ArrayList<>(List.of(name.getReferencedName()));
            }
            if (value instanceof KtQualifiedExpression qualified) {
                List<String> receiver = receiverPath(qualified.getReceiverExpression());
                List<String> selector = receiverPath(qualified.getSelectorExpression());
                if (receiver.isEmpty() || selector.isEmpty()) return List.of();
                receiver.addAll(selector);
                return receiver;
            }
            return List.of();
        }

        private void name(KtNameReferenceExpression name) {
            PsiElement parent = name.getParent();
            String text = name.getReferencedName();
            if (parent instanceof KtThisExpression || parent instanceof KtSuperExpression || parent instanceof KtInstanceExpressionWithLabel) return;
            if (parent instanceof KtCallExpression call && call.getCalleeExpression() == name) return;
            if (parent instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() == name) {
                selector(name, text, false);
                return;
            }
            if (parent instanceof KtQualifiedExpression qualified && qualified.getReceiverExpression() == name) {
                Ref ref = ref(name, text, "recv");
                ref.chain = receiverChain(name);
                bind(ref, name.getTextRange().getStartOffset(), -1);
                return;
            }
            Ref ref = ref(name, text, "name");
            bind(ref, name.getTextRange().getStartOffset(), -1);
        }

        /** The names a receiver starts, up to the first call: a.b.c(x).d is a, b, c. */
        private static List<String> receiverChain(KtNameReferenceExpression name) {
            List<String> chain = new ArrayList<>(List.of(name.getReferencedName()));
            PsiElement at = name;
            while (at.getParent() instanceof KtDotQualifiedExpression qualified && qualified.getReceiverExpression() == at) {
                KtExpression selector = qualified.getSelectorExpression();
                if (selector instanceof KtNameReferenceExpression next) {
                    chain.add(next.getReferencedName());
                } else if (selector instanceof KtCallExpression call && call.getCalleeExpression() instanceof KtNameReferenceExpression callee) {
                    chain.add(callee.getReferencedName());
                    break;
                } else {
                    break;
                }
                at = qualified;
            }
            return chain;
        }

        /** A name after a receiver: a member of what the receiver is, or an extension. Its type is not known here. */
        private Ref selector(KtNameReferenceExpression name, String text, boolean called) {
            Ref ref = ref(name, text, "sel");
            ref.called = called;
            return ref;
        }

        private void call(KtCallExpression call) {
            KtExpression callee = call.getCalleeExpression();
            int arguments = (call.getValueArgumentList() == null ? 0 : call.getValueArgumentList().getArguments().size()) + call.getLambdaArguments().size();
            for (KtValueArgument argument : call.getValueArguments()) if (argument.getSpreadElement() != null) arguments = -1;
            ref(call, "invoke", "op");
            if (callee instanceof KtNameReferenceExpression name) {
                String text = name.getReferencedName();
                if (call.getParent() instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() == call) {
                    selector(name, text, true).arguments = arguments;
                } else {
                    Ref ref = ref(name, text, "call");
                    ref.called = true;
                    ref.arguments = arguments;
                    bind(ref, name.getTextRange().getStartOffset(), arguments);
                }
                reflection(call, text, call.getParent() instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() == call ? qualified.getReceiverExpression() : null);
            } else {
                visit(callee);
            }
            if (call.getTypeArgumentList() != null) visit(call.getTypeArgumentList());
            if (call.getValueArgumentList() != null) visit(call.getValueArgumentList());
            for (KtLambdaArgument lambda : call.getLambdaArguments()) visit(lambda);
            if (callee instanceof KtNameReferenceExpression name) {
                KtExpression receiver = call.getParent() instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() == call ? qualified.getReceiverExpression() : null;
                List<String> names = callNames(name.getReferencedName(), receiver == null ? null : receiverPath(receiver));
                if (RESOURCE_LOADS.contains(names.get(names.size() - 1))) resourceLoad(call);
            }
        }

        private void reflection(KtCallExpression call, String name, KtExpression receiver) {
            List<String> receiverNames = receiver == null ? null : receiverPath(receiver);
            List<String> names = callNames(name, receiverNames);
            name = names.get(names.size() - 1);
            String last = names.size() < 2 ? null : names.get(names.size() - 2);
            List<KtValueArgument> arguments = call.getValueArguments();
            if (CLASS_LOADS.contains(name) && (!name.equals("forName") || receiver == null || "Class".equals(last))) {
                String target = arguments.isEmpty() ? null : literal(arguments.get(0).getArgumentExpression());
                reflection(call, "class", target, null, call.getText());
            } else if (name.equals("load") && "ServiceLoader".equals(last) || name.equals("loadInstalled") && "ServiceLoader".equals(last)) {
                reflection(call, "service", null, null, call.getText());
            } else if (nativeLoad(names)) {
                reflection(call, "native", null, null, call.getText());
            } else if (MEMBER_LOOKUPS.contains(name) && name.startsWith("find") && !arguments.isEmpty()) {
                classTarget(call, arguments.get(0).getArgumentExpression());
            }
        }

        /** getDeclaredMethod and the like, on a class named in the code. */
        private void memberReflection(KtQualifiedExpression qualified) {
            KtExpression selector = qualified.getSelectorExpression();
            String name = selector instanceof KtCallExpression call && call.getCalleeExpression() instanceof KtNameReferenceExpression callee ? callee.getReferencedName()
                : selector instanceof KtNameReferenceExpression reference ? reference.getReferencedName() : null;
            if (name == null) return;
            name = importedName(name);
            if (!MEMBER_LOOKUPS.contains(name) || name.startsWith("find")) return;
            classTarget(qualified, qualified.getReceiverExpression());
        }

        private void classTarget(KtExpression at, KtExpression receiver) {
            KtExpression value = bare(receiver);
            while (value instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() instanceof KtNameReferenceExpression selector
                && Set.of("java", "kotlin", "javaObjectType", "javaPrimitiveType").contains(selector.getReferencedName())) {
                value = qualified.getReceiverExpression();
            }
            if (value instanceof KtClassLiteralExpression literal && chainOf(literal.getReceiverExpression()) != null) {
                reflection(at, "member", null, chainOf(literal.getReceiverExpression()), at.getText());
            } else if (value instanceof KtNameReferenceExpression name && name.getReferencedName().equals("javaClass")
                || value instanceof KtQualifiedExpression qualified && qualified.getReceiverExpression() instanceof KtThisExpression
                && qualified.getSelectorExpression() instanceof KtNameReferenceExpression name2 && name2.getReferencedName().equals("javaClass")) {
                reflection(at, "member", null, List.of("this"), at.getText());
            } else if (value instanceof KtQualifiedExpression qualified && qualified.getSelectorExpression() instanceof KtCallExpression call
                && call.getCalleeExpression() instanceof KtNameReferenceExpression callee && CLASS_LOADS.contains(importedName(callee.getReferencedName())) && !call.getValueArguments().isEmpty()) {
                reflection(at, "member", literal(call.getValueArguments().get(0).getArgumentExpression()), null, at.getText());
            } else {
                reflection(at, "member", null, null, at.getText());
            }
        }

        private void resourceLoad(KtCallExpression call) {
            List<KtValueArgument> arguments = call.getValueArguments();
            KtExpression argument = arguments.isEmpty() ? null : bare(arguments.get(0).getArgumentExpression());
            String name = literal(argument);
            Integer ref = null;
            if (name == null && argument != null && chainOf(argument) != null) ref = refAt.get(leftmost(argument));
            resource(call, name, ref, call.getText());
        }

        private void callable(KtCallableReferenceExpression reference) {
            KtExpression receiver = reference.getReceiverExpression();
            String text = reference.getCallableReference().getReferencedName();
            List<String> names = callNames(text, receiver == null ? null : receiverPath(receiver));
            String canonical = names.get(names.size() - 1);
            String last = names.size() < 2 ? null : names.get(names.size() - 2);
            if ((canonical.equals("load") || canonical.equals("loadInstalled")) && "ServiceLoader".equals(last)) {
                reflection(reference, "service", null, null, reference.getText());
            } else if (nativeLoad(names)) {
                reflection(reference, "native", null, null, reference.getText());
            } else if (CLASS_LOADS.contains(canonical) || MEMBER_LOOKUPS.contains(canonical) || RESOURCE_LOADS.contains(canonical)) {
                reflection(reference, CLASS_LOADS.contains(canonical) ? "class" : RESOURCE_LOADS.contains(canonical) ? "resource" : "member", null, null, reference.getText());
            }
            if (receiver == null) {
                Ref ref = ref(reference.getCallableReference(), text, "callable");
                bind(ref, reference.getTextRange().getStartOffset(), -1);
                return;
            }
            List<String> chain = chainOf(receiver);
            if (chain != null) {
                Ref ref = ref(leftmost(receiver), chain.get(0), "recv");
                chain.add(text);
                ref.chain = chain;
                bind(ref, receiver.getTextRange().getStartOffset(), -1);
            } else {
                visit(receiver);
            }
            ref(reference.getCallableReference(), text, "sel");
        }

        private static final Map<IElementType, List<String>> OPERATORS = new HashMap<>();

        static {
            OPERATORS.put(KtTokens.PLUS, List.of("plus"));
            OPERATORS.put(KtTokens.MINUS, List.of("minus"));
            OPERATORS.put(KtTokens.MUL, List.of("times"));
            OPERATORS.put(KtTokens.DIV, List.of("div"));
            OPERATORS.put(KtTokens.PERC, List.of("rem", "mod"));
            OPERATORS.put(KtTokens.RANGE, List.of("rangeTo"));
            OPERATORS.put(KtTokens.RANGE_UNTIL, List.of("rangeUntil"));
            OPERATORS.put(KtTokens.IN_KEYWORD, List.of("contains"));
            OPERATORS.put(KtTokens.NOT_IN, List.of("contains"));
            OPERATORS.put(KtTokens.LT, List.of("compareTo"));
            OPERATORS.put(KtTokens.GT, List.of("compareTo"));
            OPERATORS.put(KtTokens.LTEQ, List.of("compareTo"));
            OPERATORS.put(KtTokens.GTEQ, List.of("compareTo"));
            OPERATORS.put(KtTokens.PLUSEQ, List.of("plusAssign", "plus"));
            OPERATORS.put(KtTokens.MINUSEQ, List.of("minusAssign", "minus"));
            OPERATORS.put(KtTokens.MULTEQ, List.of("timesAssign", "times"));
            OPERATORS.put(KtTokens.DIVEQ, List.of("divAssign", "div"));
            OPERATORS.put(KtTokens.PERCEQ, List.of("remAssign", "rem", "modAssign", "mod"));
            OPERATORS.put(KtTokens.EXCL, List.of("not"));
            OPERATORS.put(KtTokens.PLUSPLUS, List.of("inc"));
            OPERATORS.put(KtTokens.MINUSMINUS, List.of("dec"));
        }

        private void operation(KtOperationReferenceExpression operation) {
            IElementType token = operation.getOperationSignTokenType();
            if (token == null) {
                // An infix call by name: a joinedWith b.
                Ref ref = ref(operation, operation.getReferencedName(), "sel");
                ref.called = true;
                ref.arguments = 1;
                return;
            }
            List<String> names = OPERATORS.getOrDefault(token, List.of());
            if (operation.getParent() instanceof KtUnaryExpression) {
                names = token == KtTokens.PLUS ? List.of("unaryPlus") : token == KtTokens.MINUS ? List.of("unaryMinus") : names;
            }
            for (String name : names) ref(operation, name, "op");
        }

        static KtExpression bare(KtExpression expression) {
            return expression == null ? null : KtPsiUtil.safeDeparenthesize(expression);
        }

        /** A string the code spells out whole: a literal, or literals joined with +. Null for anything computed. */
        static String literal(KtExpression expression) {
            KtExpression value = bare(expression);
            if (value instanceof KtStringTemplateExpression template) {
                StringBuilder text = new StringBuilder();
                for (KtStringTemplateEntry entry : template.getEntries()) {
                    if (entry instanceof KtLiteralStringTemplateEntry) text.append(entry.getText());
                    else if (entry instanceof KtEscapeStringTemplateEntry escape) text.append(escape.getUnescapedValue());
                    else return null;
                }
                return text.toString();
            }
            if (value instanceof KtBinaryExpression binary && binary.getOperationToken() == KtTokens.PLUS) {
                String left = literal(binary.getLeft());
                String right = literal(binary.getRight());
                return left == null || right == null ? null : left + right;
            }
            return null;
        }
    }

    static final class JavaFile extends SourceFile {
        private final PsiJavaFile java;

        JavaFile(Json json, PsiJavaFile file) {
            super(json, file);
            this.java = file;
        }

        @Override String language() { return "java"; }

        @Override String facade() { return null; }

        @Override void write() {
            errors();
            packageName = java.getPackageName();
            PsiImportList list = java.getImportList();
            if (list != null) {
                for (PsiImportStatementBase statement : list.getAllImportStatements()) {
                    PsiJavaCodeReferenceElement reference = statement.getImportReference();
                    if (reference == null) continue;
                    String path = reference.getQualifiedName();
                    boolean isStatic = statement instanceof PsiImportStaticStatement;
                    imports.add(new String[]{path, null, statement.isOnDemand() ? (isStatic ? "static*" : "*") : isStatic ? "static" : null});
                }
            }
            Scope top = new Scope("file", false);
            scopes.push(top);
            for (PsiClass type : java.getClasses()) top.add(type.getName(), new Binding(-1, "class", 0));
            for (PsiClass type : java.getClasses()) type(type);
            scopes.pop();
            super.write();
        }

        private void type(PsiClass type) {
            Decl decl = declare(type.isInterface() ? "interface" : "class", type.getName() == null ? "" : type.getName(), type);
            if (type.hasModifierProperty(PsiModifier.PRIVATE)) decl.flags.add("private");
            if (type.isEnum()) decl.flags.add("enum");
            if (type.getExtendsListTypes().length > 0 || type.getImplementsListTypes().length > 0) decl.flags.add("supertypes");
            if (!type.isInterface() && !type.isEnum() && !type.hasModifierProperty(PsiModifier.ABSTRACT)) {
                decl.arity = new ArrayList<>();
                for (PsiMethod constructor : type.getConstructors()) decl.arity.add(range(constructor));
                if (decl.arity.isEmpty()) decl.arity.add(new int[]{0, 0});
            }
            owners.push(decl.id);
            Scope body = new Scope("class", type.hasModifierProperty(PsiModifier.STATIC) || type.getContainingClass() == null);
            for (PsiField field : type.getFields()) {
                Decl member = declare("property", field.getName(), field);
                if (field.hasModifierProperty(PsiModifier.STATIC)) member.flags.add("static");
                if (field.hasModifierProperty(PsiModifier.PRIVATE)) member.flags.add("private");
                if (field.getInitializer() != null) member.value = literal(field.getInitializer());
                body.add(field.getName(), new Binding(member.id, "property", 0));
            }
            for (PsiMethod method : type.getMethods()) {
                Binding binding = new Binding(-1, "function", 0);
                binding.required = 0;
                body.add(method.getName(), binding);
            }
            for (PsiClass inner : type.getInnerClasses()) body.add(inner.getName(), new Binding(-1, "class", 0));
            for (PsiTypeParameter parameter : type.getTypeParameters()) body.add(parameter.getName(), new Binding(-1, "typeparameter", 0));
            scopes.push(body);
            for (PsiElement child = type.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof PsiClass inner) {
                    type(inner);
                } else if (child instanceof PsiMethod method) {
                    Decl member = declare(method.isConstructor() ? "constructor" : "function", method.getName(), method);
                    if (method.hasModifierProperty(PsiModifier.STATIC)) member.flags.add("static");
                    member.arity = List.of(range(method));
                    if (method.hasModifierProperty(PsiModifier.NATIVE)) member.flags.add("native");
                    if (method.hasModifierProperty(PsiModifier.PRIVATE)) member.flags.add("private");
                    owners.push(member.id);
                    scopes.push(new Scope("function", false));
                    for (PsiTypeParameter parameter : method.getTypeParameters()) scopes.peek().add(parameter.getName(), new Binding(-1, "typeparameter", 0));
                    visit(method);
                    scopes.pop();
                    owners.pop();
                } else {
                    visit(child);
                }
            }
            scopes.pop();
            owners.pop();
        }

        private void visit(PsiElement element) {
            if (element == null || element instanceof PsiWhiteSpace || element instanceof PsiComment) return;
            if (element instanceof PsiPackageStatement || element instanceof PsiImportList) return;
            if (element instanceof PsiClass type) {
                if (!(element instanceof PsiTypeParameter)) {
                    scopes.peek().add(type.getName(), new Binding(-1, "class", 0));
                    type(type);
                }
            } else if (element instanceof PsiCodeBlock || element instanceof PsiForStatement || element instanceof PsiForeachStatement
                || element instanceof PsiLambdaExpression || element instanceof PsiCatchSection || element instanceof PsiTryStatement || element instanceof PsiSwitchBlock) {
                scopes.push(new Scope("block", false));
                for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) visit(child);
                scopes.pop();
            } else if (element instanceof PsiVariable variable && !(element instanceof PsiField)) {
                for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) visit(child);
                scopes.peek().add(variable.getName(), new Binding(-1, "local", element.getTextRange().getEndOffset()));
            } else if (element instanceof PsiMethodCallExpression call) {
                visitChildren(call);
                methodCall(call);
            } else if (element instanceof PsiReferenceExpression reference) {
                reference(reference);
            } else if (element instanceof PsiJavaCodeReferenceElement reference) {
                String path = reference.getQualifiedName();
                if (path != null && !path.isEmpty()) {
                    List<String> chain = new ArrayList<>(List.of(path.split("\\.")));
                    Ref ref = ref(reference, chain.get(0), "type");
                    ref.chain = chain;
                    bind(ref, reference.getTextRange().getStartOffset(), -1);
                }
                if (reference.getParameterList() != null) visit(reference.getParameterList());
            } else {
                visitChildren(element);
            }
        }

        private void visitChildren(PsiElement element) {
            for (PsiElement child = element.getFirstChild(); child != null; child = child.getNextSibling()) visit(child);
        }

        private static int[] range(PsiMethod method) {
            int count = method.getParameterList().getParametersCount();
            return method.isVarArgs() ? new int[]{count - 1, -1} : new int[]{count, count};
        }

        private static int arguments(PsiReferenceExpression reference) {
            return reference.getParent() instanceof PsiMethodCallExpression call ? call.getArgumentList().getExpressionCount() : -1;
        }

        private static List<String> receiverPath(PsiExpression expression) {
            if (expression instanceof PsiParenthesizedExpression parentheses) return receiverPath(parentheses.getExpression());
            if (expression instanceof PsiMethodCallExpression call) return receiverPath(call.getMethodExpression());
            if (expression instanceof PsiReferenceExpression reference && reference.getReferenceName() != null) {
                PsiExpression qualifier = reference.getQualifierExpression();
                List<String> receiver = qualifier == null ? new ArrayList<>() : receiverPath(qualifier);
                if (qualifier != null && receiver.isEmpty()) return List.of();
                receiver.add(reference.getReferenceName());
                return receiver;
            }
            return List.of();
        }

        private void reference(PsiReferenceExpression reference) {
            PsiExpression qualifier = reference.getQualifierExpression();
            String name = reference.getReferenceName();
            if (name == null) {
                visitChildren(reference);
                return;
            }
            if (reference instanceof PsiMethodReferenceExpression method) {
                List<String> receiver = method.getQualifierType() != null ? List.of(method.getQualifierType().getText().split("\\.")) : qualifier == null ? null : receiverPath(qualifier);
                List<String> names = callNames(name, receiver);
                String canonical = names.get(names.size() - 1);
                String last = names.size() < 2 ? null : names.get(names.size() - 2);
                if ((canonical.equals("load") || canonical.equals("loadInstalled")) && "ServiceLoader".equals(last)) {
                    reflection(method, "service", null, null, method.getText());
                } else if (nativeLoad(names)) {
                    reflection(method, "native", null, null, method.getText());
                } else if (CLASS_LOADS.contains(canonical) || MEMBER_LOOKUPS.contains(canonical) || RESOURCE_LOADS.contains(canonical)) {
                    reflection(method, CLASS_LOADS.contains(canonical) ? "class" : RESOURCE_LOADS.contains(canonical) ? "resource" : "member", null, null, method.getText());
                }
            }
            boolean called = reference.getParent() instanceof PsiMethodCallExpression;
            if (qualifier == null) {
                List<String> chain = new ArrayList<>(List.of(name));
                PsiElement at = reference;
                while (at.getParent() instanceof PsiReferenceExpression outer && outer.getQualifierExpression() == at && outer.getReferenceName() != null) {
                    chain.add(outer.getReferenceName());
                    at = outer;
                }
                Ref ref = ref(reference, name, called ? "call" : chain.size() > 1 ? "recv" : "name");
                ref.called = called;
                ref.arguments = arguments(reference);
                ref.chain = chain.size() > 1 ? chain : null;
                bindJava(ref, reference.getTextRange().getStartOffset());
                return;
            }
            visit(qualifier);
            if (reference.getParameterList() != null) visit(reference.getParameterList());
            Ref ref = ref(reference, name, "sel");
            ref.called = called;
            ref.arguments = arguments(reference);
        }

        /** Java binds a name to a variable, then to a method of the innermost class that has one by that name. */
        private void bindJava(Ref ref, int offset) {
            for (Scope scope : scopes) {
                for (Binding binding : scope.names.getOrDefault(ref.name, List.of())) {
                    if (binding.from > offset) continue;
                    boolean method = binding.what.equals("function");
                    if (ref.called != method) continue;
                    if (binding.decl >= 0) ref.binds.add(binding.decl);
                    ref.certain = 1;
                }
                if (ref.certain > 0) return;
            }
        }

        private void methodCall(PsiMethodCallExpression call) {
            PsiReferenceExpression method = call.getMethodExpression();
            String name = method.getReferenceName();
            if (name == null) return;
            PsiExpression receiver = method.getQualifierExpression();
            List<String> names = callNames(name, receiver == null ? null : receiverPath(receiver));
            name = names.get(names.size() - 1);
            String last = names.size() < 2 ? null : names.get(names.size() - 2);
            PsiExpression[] arguments = call.getArgumentList().getExpressions();
            if (CLASS_LOADS.contains(name) && (!name.equals("forName") || receiver == null || "Class".equals(last))) {
                reflection(call, "class", arguments.length == 0 ? null : literal(arguments[0]), null, call.getText());
            } else if ((name.equals("load") || name.equals("loadInstalled")) && "ServiceLoader".equals(last)) {
                reflection(call, "service", null, null, call.getText());
            } else if (nativeLoad(names)) {
                reflection(call, "native", null, null, call.getText());
            } else if (MEMBER_LOOKUPS.contains(name)) {
                PsiExpression target = name.startsWith("find") && arguments.length > 0 ? arguments[0] : receiver;
                if (target instanceof PsiClassObjectAccessExpression access) {
                    reflection(call, "member", null, List.of(access.getOperand().getType().getCanonicalText().split("\\.")), call.getText());
                } else if (target instanceof PsiMethodCallExpression inner && "getClass".equals(inner.getMethodExpression().getReferenceName())
                    && inner.getMethodExpression().getQualifierExpression() == null) {
                    reflection(call, "member", null, List.of("this"), call.getText());
                } else {
                    reflection(call, "member", null, null, call.getText());
                }
            } else if (RESOURCE_LOADS.contains(name)) {
                String literal = arguments.length == 0 ? null : literal(arguments[0]);
                Integer ref = null;
                if (literal == null && arguments.length > 0 && arguments[0] instanceof PsiReferenceExpression reference) {
                    PsiElement leftmost = reference;
                    while (leftmost instanceof PsiReferenceExpression inner && inner.getQualifierExpression() != null) leftmost = inner.getQualifierExpression();
                    ref = refAt.get(leftmost);
                }
                resource(call, literal, ref, call.getText());
            }
        }

        static String literal(PsiExpression expression) {
            if (expression instanceof PsiParenthesizedExpression parenthesized) return literal(parenthesized.getExpression());
            if (expression instanceof PsiLiteralExpression literal && literal.getValue() instanceof String text) return text;
            if (expression instanceof PsiPolyadicExpression polyadic && polyadic.getOperationTokenType() == JavaTokenType.PLUS) {
                StringBuilder text = new StringBuilder();
                for (PsiExpression operand : polyadic.getOperands()) {
                    String part = literal(operand);
                    if (part == null) return null;
                    text.append(part);
                }
                return text.toString();
            }
            return null;
        }
    }
}
