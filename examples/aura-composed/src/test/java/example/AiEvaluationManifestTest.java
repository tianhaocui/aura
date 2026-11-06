package example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
        assertThat(manifest.getInteger("schemaVersion")).isEqualTo(2);
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

    /**
     * A budget is the one number in the task set that can quietly decide the experiment: the pilot
     * recorded 18,679 tokens against a 4,000 `maxTokens` that nothing had derived, and no staged recipe
     * shipping `AI_GUIDE.md` can fit inside such a number, because its own first call is already about
     * 8,100 prompt tokens. So the budget is recomputed here from the declared context and the declared
     * repair allowance, and a hand-typed value fails the manifest instead of silently grading a cohort.
     */
    @Test
    void everyTokenBudgetIsTheDerivedWorstCaseOfItsOwnTask() throws Exception {
        Path root = repoRoot();
        JSONObject manifest = JSON.parseObject(Files.readString(root.resolve("evals/tasks.json")));
        JSONObject budget = manifest.getJSONObject("budget");
        assertThat(budget).as("evals/tasks.json declares no budget derivation").isNotNull();
        BigDecimal tokensPerByte = budget.getBigDecimal("tokensPerByte");
        int outputPerCall = budget.getIntValue("maxOutputTokensPerCall");
        assertThat(tokensPerByte).as("budget.tokensPerByte").isPositive();
        assertThat(outputPerCall).as("budget.maxOutputTokensPerCall").isPositive();
        int contextBytes = stagedContextBytes(root, budget.getJSONArray("stagedContext"));
        int calls = 1 + manifest.getIntValue("maxRepairRounds");
        for (Object value : manifest.getJSONArray("tasks")) {
            JSONObject task = (JSONObject) value;
            int promptBytes = contextBytes + task.getString("prompt").getBytes(StandardCharsets.UTF_8).length;
            int perCall = ceilMultiply(promptBytes, tokensPerByte) + outputPerCall;
            assertThat(task.getIntValue("maxTokens"))
                    .as("task '%s' budget: %d calls x %d tokens, recomputed from the declared context",
                            task.getString("id"), calls, perCall)
                    .isEqualTo(calls * perCall);
        }
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

    private static int stagedContextBytes(Path root, JSONArray declaredFiles) throws IOException {
        assertThat(declaredFiles).as("budget.stagedContext names no files").isNotEmpty();
        int bytes = 0;
        for (Object file : declaredFiles) {
            Path context = root.resolve((String) file);
            assertThat(context)
                    .as("budget.stagedContext names %s, which the tree does not hold", context)
                    .isRegularFile();
            bytes += Files.readAllBytes(context).length;
        }
        return bytes;
    }

    /** Byte counts are exact and the ratio is a declared decimal, so this never goes through a double. */
    private static int ceilMultiply(int bytes, BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(bytes)).setScale(0, RoundingMode.CEILING).intValueExact();
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
