package io.aura.processor;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuraRouteProcessorTest {
    @Test
    void generatesInspectableRouteContributorForAnnotatedService() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("JDK compiler is required for processor verification").isNotNull();
        Path root = Files.createTempDirectory("aura-processor-test");
        Path source = root.resolve("ExampleService.java");
        Files.writeString(source, """
                package sample;
                import io.aura.annotation.Get;
                import io.aura.annotation.Path;
                import io.aura.annotation.Post;
                @Path(\"/orders\")
                public class ExampleService {
                    @Get(\"/{id}\") public Order get(long id) { return new Order(id); }
                    @Post(\"\") public Order create(CreateRequest request) { return new Order(request.id()); }
                }
                record Order(long id) {}
                record CreateRequest(long id) {}
                """);
        Path classes = root.resolve("classes");
        Path generated = root.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);

        String classPath = System.getProperty("java.class.path");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            var units = files.getJavaFileObjects(source.toFile());
            var task = compiler.getTask(null, files, diagnostic -> { },
                    List.of("-classpath", classPath, "-d", classes.toString(),
                            "-s", generated.toString(), "-parameters"), null, units);
            task.setProcessors(List.of(new AuraRouteProcessor()));
            assertThat(task.call()).isTrue();
        }

        Path generatedSource = generated.resolve("sample/ExampleServiceAuraRoutes.java");
        assertThat(generatedSource).exists();
        String text = Files.readString(generatedSource);
        assertThat(text).contains("implements io.aura.contract.RouteContributor")
                .contains("GET", "/orders/{id}")
                .contains("ctx.path(\"id\")")
                .contains("ctx.bodyOrThrow(sample.CreateRequest.class)")
                .contains("new io.aura.contract.ApiParameter");
        assertThat(classes.resolve("sample/ExampleServiceAuraRoutes.class")).exists();
    }

    @Test
    void generatesPrimitiveShortAndByteBindings() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Files.createTempDirectory("aura-processor-scalar-test");
        Path source = root.resolve("ScalarService.java");
        Files.writeString(source, """
                package scalar;
                import io.aura.annotation.Get;
                public class ScalarService {
                    @Get("/values") public String values(short s, byte b, char c) { return "ok"; }
                }
                """);
        Path classes = root.resolve("classes");
        Path generated = root.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, files, diagnostic -> { },
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                            "-s", generated.toString(), "-parameters"), null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new AuraRouteProcessor()));
            assertThat(task.call()).isTrue();
        }
    }

    @Test
    void generatedBodySchemaSupportsMoreThanTenRecordFields() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Files.createTempDirectory("aura-processor-schema-test");
        Path source = root.resolve("LargeService.java");
        Files.writeString(source, """
                package large;
                import io.aura.annotation.Post;
                public class LargeService {
                    @Post("/large") public String create(LargeRequest request) { return "ok"; }
                }
                record LargeRequest(String a, String b, String c, String d, String e, String f,
                        String g, String h, String i, String j, String k, String l) {}
                """);
        Path classes = root.resolve("classes");
        Path generated = root.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, files, diagnostic -> { },
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                            "-s", generated.toString(), "-parameters"), null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new AuraRouteProcessor()));
            assertThat(task.call()).isTrue();
        }
    }

    @Test
    void generatedBodySchemaCarriesValidationConstraints() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path root = Files.createTempDirectory("aura-processor-validation-test");
        Path source = root.resolve("ValidatedService.java");
        Files.writeString(source, """
                package validated;
                import io.aura.annotation.*;
                import java.util.List;
                public class ValidatedService {
                    @Post("/validated") public String create(ValidatedRequest request) { return "ok"; }
                }
                record ValidatedRequest(@NotBlank String name, @Min(1) @Max(10) int count,
                        @Size(max=5) String code, @Pattern("[a-z]+") String slug,
                        Integer boxed, List<String> tags, Child child) {}
                record Child(String value) {}
                """);
        Path classes = root.resolve("classes");
        Path generated = root.resolve("generated");
        Files.createDirectories(classes);
        Files.createDirectories(generated);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(null, files, diagnostic -> { },
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString(),
                            "-s", generated.toString(), "-parameters"), null,
                    files.getJavaFileObjects(source.toFile()));
            task.setProcessors(List.of(new AuraRouteProcessor()));
            assertThat(task.call()).isTrue();
        }
        String generatedText = Files.readString(generated.resolve("validated/ValidatedServiceAuraRoutes.java"));
        assertThat(generatedText).contains("minimum", "maximum", "maxLength", "pattern", "required")
                .contains("java.util.List", "\"boxed\"", "\"tags\"", "\"child\"");
    }
}
