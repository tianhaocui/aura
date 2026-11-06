package example;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Consumes the recorded evaluation results, which is what turns "staged context lowers token and
 * repair cost while preserving stability" from a slogan into a claim that can fail. It is disabled
 * unless scripts/verify-ai-evals.sh passes -Daura.eval.results=true, so an unfinished experiment
 * cannot break an ordinary build; the gate then proves this class really ran by requiring a fresh
 * surefire report with skipped="0", which a class disabled by assumption would never produce.
 */
@EnabledIfSystemProperty(named = "aura.eval.results", matches = "true")
class AiEvaluationResultsTest {

    /** Only these keywords are enforced. Anything else in the schema fails the run instead of being ignored. */
    private static final Set<String> SUPPORTED_KEYWORDS =
            Set.of("type", "required", "properties", "additionalProperties", "items", "enum",
                    "minimum", "minLength", "pattern");

    /** Documentation only: these carry no constraint, so ignoring them costs nothing. */
    private static final Set<String> METADATA_KEYWORDS = Set.of("$schema", "title", "description");

    private static final String BASELINE = "baseline";
    private static final String TREATMENT = "treatment";

    /**
     * A median over one sample is that sample, so a single run per arm can show a difference but
     * cannot size one. Reduction legs are not allowed to declare the claim supported below this
     * many records per arm per task; the gate stays red at n = 1 even when every direction points
     * the right way, because "plumbing evidence" and "verdict" must not be the same green light.
     */
    private static final int MIN_SAMPLES_PER_ARM = 3;

    @Test
    void everyRecordSatisfiesTheDeclaredSchema() {
        List<Record> records = loadRecords();
        JSONObject schema = JSON.parseObject(read(repoRoot().resolve("evals/result.schema.json")));
        assertSchemaIsUnderstood(schema);
        for (Record record : records) {
            validateAgainstSchema(record.json(), schema, record.label());
        }
    }

    @Test
    void everyRecordWasProducedAgainstTheCurrentTaskSet() {
        String currentHash = sha256OfCurrentTaskSet();
        Set<String> knownTaskIds = taskIds();
        for (Record record : loadRecords()) {
            String taskId = record.json().getString("taskId");
            assertThat(knownTaskIds)
                    .as("%s names a task that is not in evals/tasks.json", record.label())
                    .contains(taskId);
            assertThat(record.json().getString("taskSetHash"))
                    .as("%s was recorded against a different task set; rerun the experiment", record.label())
                    .isEqualTo(currentHash);
        }
    }

    @Test
    void bothArmsCoverEveryMeasuredTaskAndDifferOnlyByContextRecipe() {
        Map<String, List<Record>> byTask = loadByTaskId();
        for (Map.Entry<String, List<Record>> entry : byTask.entrySet()) {
            List<Record> baseline = filter(entry.getValue(), BASELINE);
            List<Record> treatment = filter(entry.getValue(), TREATMENT);
            assertThat(baseline)
                    .as("task '%s' has no baseline arm to compare the staged arm against", entry.getKey())
                    .isNotEmpty();
            assertThat(treatment)
                    .as("task '%s' has no staged arm; the claim is not being measured", entry.getKey())
                    .isNotEmpty();
            Set<String> baselineContexts = contextIds(baseline);
            Set<String> treatmentContexts = contextIds(treatment);
            assertThat(baselineContexts)
                    .as("task '%s' mixes %s recipes, so its baseline median measures nothing",
                            entry.getKey(), BASELINE)
                    .hasSize(1);
            assertThat(treatmentContexts)
                    .as("task '%s' mixes %s recipes", entry.getKey(), TREATMENT)
                    .hasSize(1);
            assertThat(treatmentContexts)
                    .as("task '%s' records both arms under the same contextId, so the two arms are one arm",
                            entry.getKey())
                    .isNotEqualTo(baselineContexts);
            // A median is only a comparison when the model is held constant; otherwise the cheaper arm
            // may simply be the cheaper model.
            Set<String> baselineModels = models(baseline);
            Set<String> treatmentModels = models(treatment);
            assertThat(baselineModels)
                    .as("task '%s' compares staged context against baselines from different models", entry.getKey())
                    .hasSize(1);
            assertThat(treatmentModels)
                    .as("task '%s' measures staged context with more than one model", entry.getKey())
                    .hasSize(1);
            assertThat(treatmentModels)
                    .as("task '%s' pairs a %s arm recorded with %s against a %s arm recorded with %s, "
                                    + "so the token difference is the models, not the context",
                            entry.getKey(), BASELINE, baselineModels, TREATMENT, treatmentModels)
                    .isEqualTo(baselineModels);
        }
    }

    @Test
    void everyMeasuredTaskCarriesEnoughSamplesPerArm() {
        Map<String, List<Record>> byTask = loadByTaskId();
        List<String> thin = new ArrayList<>();
        for (Map.Entry<String, List<Record>> entry : byTask.entrySet()) {
            int baseline = filter(entry.getValue(), BASELINE).size();
            int treatment = filter(entry.getValue(), TREATMENT).size();
            if (baseline < MIN_SAMPLES_PER_ARM || treatment < MIN_SAMPLES_PER_ARM) {
                thin.add(String.format("%s: %d baseline, %d staged (need %d each)",
                        entry.getKey(), baseline, treatment, MIN_SAMPLES_PER_ARM));
            }
        }
        assertThat(thin)
                .as("a median of one sample is that sample, so these tasks show a difference"
                        + " without sizing one; run more samples before reading any green here as"
                        + " support for the claim")
                .isEmpty();
    }

    @Test
    void noRecordContradictsItself() {
        for (Record record : loadRecords()) {
            JSONObject json = record.json();
            int rounds = json.getIntValue("repairRounds");
            boolean firstPass = json.getBooleanValue("firstPassSuccess");
            boolean finalSuccess = json.getBooleanValue("finalSuccess");
            if (firstPass) {
                assertThat(rounds)
                        .as("%s claims a first-pass success that still needed %d repair rounds",
                                record.label(), rounds)
                        .isZero();
                assertThat(finalSuccess)
                        .as("%s claims the first answer passed and the run never succeeded", record.label())
                        .isTrue();
            }
            assertThat(json.getIntValue("inputTokens"))
                    .as("%s records no prompt charge, which means no model call was made to measure",
                            record.label())
                    .isPositive();
        }
    }

    @Test
    void stagedContextDoesNotLoseFirstPassAnswers() {
        Map<String, List<Record>> byTask = loadByTaskId();
        List<String> violations = new ArrayList<>();
        for (String taskId : byTask.keySet()) {
            double baselineRate = firstPassRate(filter(byTask.get(taskId), BASELINE));
            double treatmentRate = firstPassRate(filter(byTask.get(taskId), TREATMENT));
            if (treatmentRate < baselineRate) {
                violations.add(String.format("%s: baseline %.2f -> staged %.2f first-pass compile rate",
                        taskId, baselineRate, treatmentRate));
            }
        }
        assertThat(violations)
                .as("the staged recipe answers fewer tasks right the first time,"
                        + " which is the cost a cheaper prompt can hide")
                .isEmpty();
    }

    @Test
    void stagedContextLowersTokenCostByAtLeastTenPercent() {
        Map<String, List<Record>> byTask = loadByTaskId();
        List<String> violations = new ArrayList<>();
        for (String taskId : byTask.keySet()) {
            double baseline = median(inputTokens(filter(byTask.get(taskId), BASELINE)));
            double treatment = median(inputTokens(filter(byTask.get(taskId), TREATMENT)));
            if (!(baseline > 0 && treatment <= 0.9 * baseline)) {
                violations.add(String.format("%s: baseline %.0f -> staged %.0f (%.1f%% reduction, required >= 10.0%%)",
                        taskId, baseline, treatment, (baseline - treatment) * 100.0 / baseline));
            }
        }
        assertThat(violations)
                .as("the staged context recipe did not lower input cost as claimed")
                .isEmpty();
    }

    @Test
    void stagedContextDoesNotCostMoreRepairRounds() {
        Map<String, List<Record>> byTask = loadByTaskId();
        List<String> violations = new ArrayList<>();
        for (String taskId : byTask.keySet()) {
            double baseline = median(repairRounds(filter(byTask.get(taskId), BASELINE)));
            double treatment = median(repairRounds(filter(byTask.get(taskId), TREATMENT)));
            if (treatment > baseline) {
                violations.add(String.format("%s: baseline %.1f -> staged %.1f repair rounds",
                        taskId, baseline, treatment));
            }
        }
        assertThat(violations)
                .as("the staged context recipe needs more repair than the full-docs baseline")
                .isEmpty();
    }

    @Test
    void stagedContextDoesNotLoseStability() {
        Map<String, List<Record>> byTask = loadByTaskId();
        List<String> violations = new ArrayList<>();
        for (String taskId : byTask.keySet()) {
            List<Record> baseline = filter(byTask.get(taskId), BASELINE);
            List<Record> treatment = filter(byTask.get(taskId), TREATMENT);
            double baselineRate = successRate(baseline);
            double treatmentRate = successRate(treatment);
            if (treatmentRate < baselineRate) {
                violations.add(String.format("%s: baseline %.2f -> staged %.2f final success rate",
                        taskId, baselineRate, treatmentRate));
            }
            if (anySucceeded(baseline) && noneSucceeded(treatment)) {
                violations.add(String.format("%s: the task succeeds under full docs and never succeeds staged", taskId));
            }
        }
        assertThat(violations)
                .as("stability dropped, which the claim forbids even when tokens fall")
                .isEmpty();
    }

    @Test
    void stagedRecordsStayInsideTheirTokenBudget() {
        Map<String, Integer> budgets = tokenBudgets();
        for (Record record : loadRecords()) {
            if (!TREATMENT.equals(record.json().getString("variant"))) {
                continue;
            }
            String taskId = record.json().getString("taskId");
            int used = record.json().getIntValue("inputTokens") + record.json().getIntValue("outputTokens");
            Integer budget = budgets.get(taskId);
            assertThat(budget).as("task '%s' has no maxTokens budget", taskId).isNotNull();
            if (used > budget) {
                fail(String.format("%s: staged arm used %d tokens against a budget of %d for task '%s'",
                        record.label(), used, budget, taskId));
            }
        }
    }

    private record Record(JSONObject json, String label) {
    }

    private static List<Record> loadRecords() {
        Path directory = repoRoot().resolve("evals/results");
        List<Record> records = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".jsonl"))
                        .sorted()
                        .toList()) {
                    List<String> lines = Files.readAllLines(file);
                    for (int index = 0; index < lines.size(); index++) {
                        String line = lines.get(index).strip();
                        if (line.isEmpty()) {
                            continue;
                        }
                        String label = file.getFileName() + ":" + (index + 1);
                        try {
                            records.add(new Record(JSON.parseObject(line), label));
                        } catch (RuntimeException malformed) {
                            fail(label + " is not a JSON object: " + malformed.getMessage());
                        }
                    }
                }
            } catch (IOException unreadable) {
                fail("cannot read " + directory + ": " + unreadable);
            }
        }
        if (records.isEmpty()) {
            fail("no evaluation evidence: " + directory
                    + " holds no result records, so the staged-context claim is untested."
                    + " Run the experiment and record it with scripts/record-ai-eval.sh;"
                    + " do not add sample data to make this pass.");
        }
        return records;
    }

    private static Map<String, List<Record>> loadByTaskId() {
        Map<String, List<Record>> byTask = new LinkedHashMap<>();
        for (Record record : loadRecords()) {
            byTask.computeIfAbsent(record.json().getString("taskId"), taskId -> new ArrayList<>()).add(record);
        }
        return byTask;
    }

    private static void assertSchemaIsUnderstood(JSONObject schema) {
        assertSchemaIsUnderstood(schema, "evals/result.schema.json");
    }

    /**
     * Recursive, so a constraint cannot be hidden inside a property or an item schema where the
     * consumer would never look at it.
     */
    private static void assertSchemaIsUnderstood(JSONObject schema, String where) {
        for (String keyword : schema.keySet()) {
            if (METADATA_KEYWORDS.contains(keyword)) {
                continue;
            }
            assertThat(SUPPORTED_KEYWORDS)
                    .as("%s declares '%s', which the consumer does not enforce; implement it or drop it, "
                            + "so a constraint can never be added silently", where, keyword)
                    .contains(keyword);
        }
        JSONObject properties = schema.getJSONObject("properties");
        if (properties != null) {
            for (Map.Entry<String, Object> declared : properties.entrySet()) {
                assertSchemaIsUnderstood((JSONObject) declared.getValue(),
                        where + " properties." + declared.getKey());
            }
        }
        JSONObject items = schema.getJSONObject("items");
        if (items != null) {
            assertSchemaIsUnderstood(items, where + " items");
        }
    }

    private static void validateAgainstSchema(JSONObject record, JSONObject schema, String label) {
        for (String keyword : schema.keySet()) {
            switch (keyword) {
                case "type" -> assertThat(schema.getString("type"))
                        .as("%s is not the object the schema describes", label)
                        .isEqualTo("object");
                case "required" -> {
                    for (Object name : schema.getJSONArray("required")) {
                        assertThat(record.containsKey((String) name))
                                .as("%s is missing the required property '%s'", label, name)
                                .isTrue();
                    }
                }
                case "properties" -> {
                    JSONObject properties = schema.getJSONObject("properties");
                    for (Map.Entry<String, Object> declared : properties.entrySet()) {
                        if (record.containsKey(declared.getKey())) {
                            validateProperty(record, declared.getKey(), (JSONObject) declared.getValue(), label);
                        }
                    }
                }
                case "additionalProperties" -> {
                    if (Boolean.FALSE.equals(schema.getBoolean("additionalProperties"))) {
                        Set<String> allowed = schema.getJSONObject("properties").keySet();
                        for (String actual : record.keySet()) {
                            assertThat(allowed)
                                    .as("%s carries '%s', which the schema does not declare", label, actual)
                                    .contains(actual);
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void validateProperty(JSONObject record, String name, JSONObject rules, String label) {
        Object value = record.get(name);
        String type = rules.getString("type");
        if (type != null) {
            switch (type) {
                case "string" -> assertThat(value).as("%s.%s", label, name).isInstanceOf(String.class);
                case "integer" -> assertThat(value).as("%s.%s", label, name)
                        .isInstanceOfAny(Integer.class, Long.class);
                case "boolean" -> assertThat(value).as("%s.%s", label, name).isInstanceOf(Boolean.class);
                case "array" -> assertThat(value).as("%s.%s", label, name).isInstanceOf(JSONArray.class);
                case "object" -> assertThat(value).as("%s.%s", label, name).isInstanceOf(JSONObject.class);
                default -> fail(String.format("%s: schema type '%s' is not understood by the consumer", label, type));
            }
        }
        if (value instanceof String text) {
            if (rules.containsKey("minLength")) {
                assertThat(text.length()).as("%s.%s length", label, name)
                        .isGreaterThanOrEqualTo(rules.getIntValue("minLength"));
            }
            if (rules.containsKey("pattern")) {
                assertThat(Pattern.compile(rules.getString("pattern")).matcher(text).matches())
                        .as("%s.%s='%s' does not match the schema pattern", label, name, text)
                        .isTrue();
            }
        }
        if (value instanceof Number number && rules.containsKey("minimum")) {
            assertThat(number.doubleValue()).as("%s.%s", label, name)
                    .isGreaterThanOrEqualTo(rules.getDoubleValue("minimum"));
        }
        if (rules.containsKey("enum")) {
            assertThat(rules.getJSONArray("enum"))
                    .as("%s.%s='%s' is outside the declared enum", label, name, value)
                    .contains(value);
        }
        if (value instanceof JSONArray items && rules.containsKey("items")) {
            String itemType = rules.getJSONObject("items").getString("type");
            for (Object item : items) {
                if ("string".equals(itemType)) {
                    assertThat(item).as("%s.%s element", label, name).isInstanceOf(String.class);
                }
            }
        }
    }

    private static Set<String> taskIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Object value : tasks().getJSONArray("tasks")) {
            ids.add(((JSONObject) value).getString("id"));
        }
        return ids;
    }

    private static Map<String, Integer> tokenBudgets() {
        Map<String, Integer> budgets = new LinkedHashMap<>();
        for (Object value : tasks().getJSONArray("tasks")) {
            JSONObject task = (JSONObject) value;
            budgets.put(task.getString("id"), task.getInteger("maxTokens"));
        }
        return budgets;
    }

    private static JSONObject tasks() {
        return JSON.parseObject(read(repoRoot().resolve("evals/tasks.json")));
    }

    private static String sha256OfCurrentTaskSet() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    Files.readAllBytes(repoRoot().resolve("evals/tasks.json"))));
        } catch (Exception unavailable) {
            throw new IllegalStateException("cannot hash evals/tasks.json", unavailable);
        }
    }

    private static List<Record> filter(List<Record> records, String variant) {
        return records.stream()
                .filter(record -> variant.equals(record.json().getString("variant")))
                .toList();
    }

    private static Set<String> contextIds(List<Record> records) {
        return records.stream().map(record -> record.json().getString("contextId"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> models(List<Record> records) {
        return records.stream().map(record -> record.json().getString("model"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<Integer> inputTokens(List<Record> records) {
        return records.stream().map(record -> record.json().getIntValue("inputTokens")).toList();
    }

    private static List<Integer> repairRounds(List<Record> records) {
        return records.stream().map(record -> record.json().getIntValue("repairRounds")).toList();
    }

    private static double successRate(List<Record> records) {
        long succeeded = records.stream().filter(record -> record.json().getBooleanValue("finalSuccess")).count();
        return (double) succeeded / records.size();
    }

    private static double firstPassRate(List<Record> records) {
        long passed = records.stream().filter(record -> record.json().getBooleanValue("firstPassSuccess")).count();
        return (double) passed / records.size();
    }

    private static boolean anySucceeded(List<Record> records) {
        return records.stream().anyMatch(record -> record.json().getBooleanValue("finalSuccess"));
    }

    private static boolean noneSucceeded(List<Record> records) {
        return !anySucceeded(records);
    }

    private static double median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new IllegalStateException("cannot read " + path, unreadable);
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
