package io.aura.processor;

import io.aura.annotation.Delete;
import io.aura.annotation.Get;
import io.aura.annotation.Head;
import io.aura.annotation.Options;
import io.aura.annotation.Patch;
import io.aura.annotation.Post;
import io.aura.annotation.Put;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional compile-time generator for explicit Aura route contributions.
 * Generated code is ordinary Java and can be inspected, compiled, and tested
 * without reflection at runtime.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedAnnotationTypes({
        "io.aura.annotation.Path", "io.aura.annotation.Get", "io.aura.annotation.Post",
        "io.aura.annotation.Put", "io.aura.annotation.Delete", "io.aura.annotation.Patch",
        "io.aura.annotation.Head", "io.aura.annotation.Options"})
public final class AuraRouteProcessor extends AbstractProcessor {
    private final Set<String> generated = new LinkedHashSet<>();

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) return false;
        Set<Element> services = new LinkedHashSet<>();
        for (Element element : roundEnv.getElementsAnnotatedWith(io.aura.annotation.Path.class)) {
            if (element.getKind() == ElementKind.CLASS) services.add(element);
        }
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.METHOD && element.getEnclosingElement().getKind() == ElementKind.CLASS) {
                    services.add(element.getEnclosingElement());
                }
            }
        }
        services.stream().filter(element -> element instanceof TypeElement)
                .map(element -> (TypeElement) element)
                .sorted(Comparator.comparing(element -> element.getQualifiedName().toString()))
                .forEach(this::generate);
        return false;
    }

    private void generate(TypeElement service) {
        String packageName = processingEnv.getElementUtils().getPackageOf(service).getQualifiedName().toString();
        String simpleName = service.getSimpleName() + "AuraRoutes";
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        if (!generated.add(qualifiedName)) return;

        String prefix = annotationValue(service, "io.aura.annotation.Path", "value");
        if (prefix == null) prefix = "";
        List<ExecutableElement> methods = service.getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element)
                .filter(method -> method.getModifiers().contains(Modifier.PUBLIC))
                .filter(this::hasRoute)
                .sorted(Comparator.comparing(method -> method.getSimpleName().toString()))
                .toList();
        if (methods.isEmpty()) return;

        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) source.append("package ").append(packageName).append(";\n\n");
        source.append("public final class ").append(simpleName)
                .append(" implements io.aura.contract.RouteContributor {\n")
                .append("  private final ").append(service.getQualifiedName()).append(" service;\n")
                .append("  public ").append(simpleName).append("(").append(service.getQualifiedName()).append(" service) { this.service = service; }\n")
                .append("  @Override public java.util.List<io.aura.contract.RouteContribution> routes() {\n")
                .append("    return java.util.List.of(\n");
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) source.append(",\n");
            appendRoute(source, prefix, methods.get(i));
        }
        source.append(");\n  }\n}\n");

        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, service);
            try (Writer writer = file.openWriter()) { writer.write(source.toString()); }
        } catch (IOException error) {
            processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                    "Aura route generation failed for " + service.getQualifiedName() + ": " + error.getMessage(), service);
        }
    }

    private void appendRoute(StringBuilder source, String prefix, ExecutableElement method) {
        Route route = route(method);
        String path = prefix + route.path;
        if (path.isEmpty()) path = "/";
        source.append("      new io.aura.contract.RouteContribution(\"").append(route.method).append("\", \"")
                .append(escape(path)).append("\", (io.aura.web.BaseHandler) ctx -> {");

        List<? extends VariableElement> parameters = method.getParameters();
        List<String> args = new ArrayList<>();
        for (VariableElement parameter : parameters) {
            TypeMirror type = parameter.asType();
            if (isContext(type)) {
                args.add("ctx");
            } else if (isBody(type)) {
                if (isSimpleBodyType(type)) {
                    args.add("ctx.body(" + rawClassName(type) + ".class)");
                } else if (type instanceof javax.lang.model.type.DeclaredType declared
                        && supportedTypeArguments(declared)) {
                    args.add("ctx.body(" + genericTypeExpression(declared) + ")");
                } else {
                    processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                            "Unsupported generic body parameter; use concrete type arguments", parameter);
                    args.add("null");
                }
            } else {
                String name = parameter.getSimpleName().toString();
                String accessor = "ctx.query(\"" + escape(name) + "\")";
                String expression = path.contains("{" + name + "}")
                        ? scalar("ctx.path(\"" + escape(name) + "\")", type)
                        : scalar(accessor, type);
                args.add(expression);
            }
        }
        String invocation = "service." + method.getSimpleName() + "(" + String.join(", ", args) + ")";
        if (method.getReturnType().getKind() == TypeKind.VOID) source.append(invocation).append(";");
        else source.append("io.aura.web.RouteResponse.write(ctx, ").append(invocation).append(");");
        source.append("}, java.util.Map.of(\"parameters\", java.util.List.of(");
        List<String> contracts = new ArrayList<>();
        for (VariableElement parameter : parameters) {
            if (isContext(parameter.asType())) continue;
            String name = parameter.getSimpleName().toString();
            String sourceName = isBody(parameter.asType()) ? "body" :
                    path.contains("{" + name + "}") ? "path" : "query";
            boolean required = isBody(parameter.asType()) || parameter.asType().getKind().isPrimitive()
                    || "path".equals(sourceName);
            String description = annotationValue(parameter, "io.aura.annotation.Desc", "value");
            String schema = isBody(parameter.asType()) ? bodySchema(parameter.asType()) : "java.util.Map.of()";
            contracts.add("new io.aura.contract.ApiParameter(\"" + escape(name) + "\", \""
                    + escape(parameter.asType().toString()) + "\", \"" + sourceName + "\", " + required + ", "
                    + (description == null ? "null" : "\"" + escape(description) + "\"") + ", " + schema + ")");
        }
        source.append(String.join(", ", contracts)).append("), \"returnType\", \"")
                .append(escape(method.getReturnType().toString())).append("\"");
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            source.append(", \"responseSchema\", ").append(bodySchema(method.getReturnType()));
        }
        String description = routeDescription(method);
        if (description != null) {
            source.append(", \"description\", \"").append(escape(description)).append("\"");
        }
        source.append("))");
    }

    private boolean hasRoute(ExecutableElement method) {
        return route(method).method != null;
    }

    private Route route(ExecutableElement method) {
        String[][] values = {
                {"io.aura.annotation.Get", "GET"}, {"io.aura.annotation.Post", "POST"},
                {"io.aura.annotation.Put", "PUT"}, {"io.aura.annotation.Delete", "DELETE"},
                {"io.aura.annotation.Patch", "PATCH"}, {"io.aura.annotation.Head", "HEAD"},
                {"io.aura.annotation.Options", "OPTIONS"}};
        for (String[] value : values) {
            String path = annotationValue(method, value[0], "value");
            if (path != null) return new Route(value[1], path);
        }
        return switch (method.getSimpleName().toString()) {
            case "get" -> new Route("GET", "/" + pathParamSegment(method));
            case "list" -> new Route("GET", "");
            case "create" -> new Route("POST", "");
            case "update" -> new Route("PUT", "/" + pathParamSegment(method));
            case "delete" -> new Route("DELETE", "/" + pathParamSegment(method));
            default -> new Route(null, null);
        };
    }

    private String pathParamSegment(ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            String type = parameter.asType().toString();
            if (Set.of("int", "long", "java.lang.String", "java.lang.Integer", "java.lang.Long")
                    .contains(type)) {
                return "{" + parameter.getSimpleName() + "}";
            }
        }
        return "{id}";
    }

    private String annotationValue(Element element, String annotationName, String valueName) {
        if (element == null) return null;
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(annotationName)) {
                for (var entry : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals(valueName)) {
                        return entry.getValue().getValue().toString();
                    }
                }
            }
        }
        return null;
    }

    private boolean isContext(TypeMirror type) {
        String name = type.toString();
        return "io.aura.web.Context".equals(name) || "io.aura.web.BaseContext".equals(name);
    }

    private boolean isBody(TypeMirror type) {
        if (isContext(type)) return false;
        // Keep this scalar set in lock-step with ParamResolver.  A scalar
        // parameter is bound from path/query; anything outside this set is a
        // request body.  Missing wrapper types here would make the generated
        // route try to deserialize a short/byte/char as JSON instead of
        // applying the same binding semantics as the reflection path.
        return !Set.of("java.lang.String", "int", "long", "boolean", "double", "float",
                "short", "byte", "char", "java.lang.Integer", "java.lang.Long",
                "java.lang.Boolean", "java.lang.Double", "java.lang.Float",
                "java.lang.Short", "java.lang.Byte", "java.lang.Character").contains(type.toString());
    }

    private boolean isSimpleBodyType(TypeMirror type) {
        return type.getKind() == TypeKind.ARRAY
                || type.getKind() == TypeKind.DECLARED
                && ((javax.lang.model.type.DeclaredType) type).getTypeArguments().isEmpty();
    }

    private boolean supportedTypeArguments(javax.lang.model.type.DeclaredType type) {
        for (TypeMirror argument : type.getTypeArguments()) {
            if (argument.getKind() == TypeKind.TYPEVAR || argument.getKind() == TypeKind.WILDCARD
                    || argument.getKind() == TypeKind.INTERSECTION) return false;
            if (argument instanceof javax.lang.model.type.DeclaredType nested
                    && !supportedTypeArguments(nested)) return false;
        }
        return true;
    }

    private String genericTypeExpression(javax.lang.model.type.DeclaredType type) {
        TypeElement raw = (TypeElement) type.asElement();
        List<String> arguments = type.getTypeArguments().stream()
                .map(this::typeExpression).toList();
        return "io.aura.web.RequestType.parameterized(" + raw.getQualifiedName() + ".class, "
                + String.join(", ", arguments) + ")";
    }

    private String typeExpression(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) return rawClassName(type) + ".class";
        if (type instanceof javax.lang.model.type.DeclaredType declared) {
            if (declared.getTypeArguments().isEmpty()) {
                return ((TypeElement) declared.asElement()).getQualifiedName() + ".class";
            }
            return genericTypeExpression(declared);
        }
        return type.toString() + ".class";
    }

    private String scalar(String value, TypeMirror type) {
        String converted = switch (type.toString()) {
            case "int", "java.lang.Integer" -> "Integer.parseInt(" + value + ")";
            case "long", "java.lang.Long" -> "Long.parseLong(" + value + ")";
            case "boolean", "java.lang.Boolean" -> "Boolean.parseBoolean(" + value + ")";
            case "double", "java.lang.Double" -> "Double.parseDouble(" + value + ")";
            case "float", "java.lang.Float" -> "Float.parseFloat(" + value + ")";
            case "short", "java.lang.Short" -> "Short.parseShort(" + value + ")";
            case "byte", "java.lang.Byte" -> "Byte.parseByte(" + value + ")";
            case "char", "java.lang.Character" -> "(" + value + ").charAt(0)";
            default -> null;
        };
        if (converted == null) return value;
        if (type.getKind().isPrimitive()) {
            String missing = switch (type.toString()) {
                case "boolean" -> "false";
                case "char" -> "'\\0'";
                case "float" -> "0.0f";
                case "double" -> "0.0d";
                case "long" -> "0L";
                default -> "0";
            };
            String absent = "(" + value + " == null";
            if ("char".equals(type.toString())) absent += " || " + value + ".isEmpty()";
            absent += " ? " + missing + " : " + converted + ")";
            return absent;
        }
        String absent = "(" + value + " == null";
        if ("java.lang.Character".equals(type.toString())) absent += " || " + value + ".isEmpty()";
        return absent + " ? null : " + converted + ")";
    }

    private String routeDescription(ExecutableElement method) {
        return annotationValue(method, "io.aura.annotation.Desc", "value");
    }

    private String bodySchema(TypeMirror type) {
        return schemaForType(type, null, 0);
    }

    private String schemaForType(TypeMirror type, Element annotations, int depth) {
        if (type == null || depth > 5) return "java.util.Map.of(\"type\", \"object\")";
        String name = type.toString();
        String jsonType = switch (name) {
            case "boolean", "java.lang.Boolean" -> "boolean";
            case "byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short",
                    "java.lang.Integer", "java.lang.Long" -> "integer";
            case "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "number";
            case "java.lang.String", "java.lang.Character", "char" -> "string";
            default -> null;
        };
        if (jsonType != null) return scalarSchema(type, annotations, jsonType);
        if (type.getKind() == TypeKind.ARRAY) {
            javax.lang.model.type.ArrayType array = (javax.lang.model.type.ArrayType) type;
            return "java.util.Map.of(\"type\", \"array\", \"items\", "
                    + schemaForType(array.getComponentType(), null, depth + 1) + ")";
        }
        if (type instanceof javax.lang.model.type.DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            if (element.getKind() == ElementKind.ENUM) {
                List<String> values = element.getEnclosedElements().stream()
                        .filter(candidate -> candidate.getKind() == ElementKind.ENUM_CONSTANT)
                        .map(candidate -> "\"" + escape(candidate.getSimpleName().toString()) + "\"")
                        .toList();
                return "java.util.Map.of(\"type\", \"string\", \"enum\", java.util.List.of("
                        + String.join(", ", values) + "))";
            }
            String rawName = element.getQualifiedName().toString();
            if (rawName.equals("java.util.List") || rawName.equals("java.util.Set")
                    || rawName.equals("java.util.Collection") || rawName.equals("java.lang.Iterable")) {
                TypeMirror item = declared.getTypeArguments().isEmpty() ? null : declared.getTypeArguments().get(0);
                return "java.util.Map.of(\"type\", \"array\", \"items\", "
                        + schemaForType(item, null, depth + 1) + ")";
            }
            if (element.getKind() == ElementKind.RECORD) {
                List<String> properties = new ArrayList<>();
                List<String> required = new ArrayList<>();
                for (Element component : element.getEnclosedElements()) {
                    if (component.getKind() != ElementKind.RECORD_COMPONENT) continue;
                    String componentName = component.getSimpleName().toString();
                    properties.add("java.util.Map.entry(\"" + escape(componentName) + "\", "
                            + schemaForType(component.asType(), component, depth + 1) + ")");
                    // Records have no field defaults in Aura's request
                    // contract.  Keep generated schemas aligned with the
                    // reflection TypeSchema path by marking every component
                    // required; BeanValidator annotations refine the value
                    // constraints but do not change presence semantics.
                    required.add("\"" + escape(componentName) + "\"");
                }
                String props = properties.isEmpty() ? "java.util.Map.of()"
                        : "java.util.Map.ofEntries(" + String.join(", ", properties) + ")";
                StringBuilder result = new StringBuilder("java.util.Map.ofEntries(")
                        .append("java.util.Map.entry(\"type\", \"object\"), java.util.Map.entry(\"properties\", ")
                        .append(props).append(")");
                if (!required.isEmpty()) result.append(", java.util.Map.entry(\"required\", java.util.List.of(")
                        .append(String.join(", ", required)).append("))");
                return result.append(")").toString();
            }
        }
        return "java.util.Map.of(\"type\", \"object\")";
    }

    private String scalarSchema(TypeMirror type, Element element) {
        String typeName = type.toString();
        String jsonType = switch (typeName) {
            case "boolean", "java.lang.Boolean" -> "boolean";
            case "byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short",
                    "java.lang.Integer", "java.lang.Long" -> "integer";
            case "float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal" -> "number";
            default -> "string";
        };
        return scalarSchema(type, element, jsonType);
    }

    private String scalarSchema(TypeMirror type, Element element, String jsonType) {
        List<String> entries = new ArrayList<>();
        entries.add("\"type\"");
        entries.add("\"" + jsonType + "\"");
        String min = annotationValue(element, "io.aura.annotation.Min", "value");
        String max = annotationValue(element, "io.aura.annotation.Max", "value");
        String sizeMin = annotationValue(element, "io.aura.annotation.Size", "min");
        String sizeMax = annotationValue(element, "io.aura.annotation.Size", "max");
        String pattern = annotationValue(element, "io.aura.annotation.Pattern", "value");
        String minLength = hasAnnotation(element, "io.aura.annotation.NotBlank") ? "1" : null;
        if (min != null) { entries.add("\"minimum\""); entries.add("Long.parseLong(\"" + escape(min) + "\")"); }
        if (max != null) { entries.add("\"maximum\""); entries.add("Long.parseLong(\"" + escape(max) + "\")"); }
        if (sizeMin != null && !"0".equals(sizeMin)) minLength = sizeMin;
        if (sizeMax != null && !"2147483647".equals(sizeMax)) { entries.add("\"maxLength\""); entries.add(sizeMax); }
        if (pattern != null) { entries.add("\"pattern\""); entries.add("\"" + escape(pattern) + "\""); }
        if (minLength != null) { entries.add("\"minLength\""); entries.add(minLength); }
        if ("array".equals(jsonType)) {
            return "java.util.Map.of(\"type\", \"array\", \"items\", java.util.Map.of(\"type\", \"string\"))";
        }
        return "java.util.Map.of(" + String.join(", ", entries) + ")";
    }

    private boolean hasAnnotation(Element element, String name) {
        return element != null && element.getAnnotationMirrors().stream()
                .anyMatch(mirror -> mirror.getAnnotationType().toString().equals(name));
    }

    private static String rawClassName(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) return type.toString();
        if (type instanceof javax.lang.model.type.DeclaredType declared) {
            return declared.asElement().toString();
        }
        return type.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private record Route(String method, String path) { }
}
