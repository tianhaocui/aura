package example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvaluationManifestTest {

    @Test
    void manifestCoversTheCanonicalTaskSetAndMetrics() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("evals/tasks.json"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root containing evals/tasks.json").isNotNull();
        JSONObject manifest = JSON.parseObject(Files.readString(root.resolve("evals/tasks.json")));
        assertThat(manifest.getInteger("schemaVersion")).isEqualTo(1);
        JSONArray tasks = manifest.getJSONArray("tasks");
        assertThat(tasks).hasSize(8);
        assertThat(tasks.stream().map(value -> ((JSONObject) value).getString("category")))
                .containsExactlyInAnyOrderElementsOf(Set.of(
                        "crud", "auth", "validation", "database",
                        "upload", "sse", "websocket", "mcp"));
        for (Object value : tasks) {
            JSONObject task = (JSONObject) value;
            assertThat(task.getString("id")).isNotBlank();
            assertThat(task.getString("prompt")).contains("Aura");
            assertThat(task.getIntValue("maxTokens")).isBetween(1, 10_000);
            assertThat(task.getJSONArray("verificationTests")).isNotEmpty();
        }

        JSONObject schema = JSON.parseObject(Files.readString(root.resolve("evals/result.schema.json")));
        assertThat(schema.getJSONArray("required")).contains(
                "inputTokens", "outputTokens", "firstPassSuccess",
                "repairRounds", "errorCodes", "durationMs");
    }
}
