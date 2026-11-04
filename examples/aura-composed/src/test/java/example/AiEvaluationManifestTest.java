package example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shape of the golden task list. Execution is deliberately not asserted here: a source
 * file existing is not the same fact as a test running, and walking the tree finds
 * aura-archetype/src/main/resources/archetype-resources, whose .java files are Velocity templates
 * that this reactor never compiles. scripts/verify-ai-evals.sh proves execution instead, by
 * requiring a surefire report that only the invocation it just ran could have written.
 */
class AiEvaluationManifestTest {

    /**
     * A package-qualified class name, so the manifest, the surefire selection, and the report file
     * name all address one and the same class. Rejects the package-less archetype template layout.
     */
    private static final String FULLY_QUALIFIED =
            "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+";

    @Test
    void manifestCoversTheCanonicalTaskSetAndMetrics() throws Exception {
        Path root = repoRoot();
        JSONObject manifest = JSON.parseObject(Files.readString(root.resolve("evals/tasks.json")));
        assertThat(manifest.getInteger("schemaVersion")).isEqualTo(1);
        // The repair loop has to be capped, or repairRounds stops meaning the same thing across runs.
        assertThat(manifest.getIntValue("maxRepairRounds")).isBetween(1, 10);
        JSONArray tasks = manifest.getJSONArray("tasks");
        assertThat(tasks).hasSize(10);
        assertThat(tasks.stream().map(value -> ((JSONObject) value).getString("category")))
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "crud", "auth", "validation", "database",
                        "upload", "sse", "websocket", "mcp",
                        "workflow", "ai"));
        for (Object value : tasks) {
            JSONObject task = (JSONObject) value;
            assertThat(task.getString("id")).isNotBlank();
            assertThat(task.getString("prompt")).contains("Aura");
            // maxTokens is the total input-plus-output budget the staged arm has to stay inside;
            // it is not a prompt length limit.
            assertThat(task.getIntValue("maxTokens")).isBetween(1, 10_000);
            assertThat(task.getJSONArray("verificationTests")).isNotEmpty();
        }

        JSONObject schema = JSON.parseObject(Files.readString(root.resolve("evals/result.schema.json")));
        assertThat(schema.getJSONArray("required")).contains(
                "inputTokens", "outputTokens", "firstPassSuccess",
                "repairRounds", "errorCodes", "durationMs",
                // A record that does not say which arm, which context recipe, which task set, and which
                // final outcome it belongs to cannot be compared with anything, so the measurement dies
                // the moment one of these becomes optional.
                "schemaVersion", "variant", "contextId", "finalSuccess", "taskSetHash");
    }

    @Test
    void everyGoldenTaskNamesAFullyQualifiedTestClass() throws Exception {
        JSONArray tasks = JSON.parseObject(Files.readString(repoRoot().resolve("evals/tasks.json")))
                .getJSONArray("tasks");
        for (Object value : tasks) {
            JSONObject task = (JSONObject) value;
            for (Object name : task.getJSONArray("verificationTests")) {
                assertThat((String) name)
                        .as("task '%s' verification test", task.getString("id"))
                        .matches(FULLY_QUALIFIED);
            }
        }
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("evals/tasks.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root containing evals/tasks.json").isNotNull();
        return root;
    }
}
