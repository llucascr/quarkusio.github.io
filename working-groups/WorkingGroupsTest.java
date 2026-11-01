import io.quarkus.qute.Engine;
import io.quarkus.qute.ReflectionValueResolver;
import io.quarkus.qute.Template;
import io.quarkus.qute.ValueResolvers;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Run with ./test.sh; compiles the real generator without starting Quarkus or calling GitHub. */
public class WorkingGroupsTest {
    private static final String LINK = "[#53093](https://github.com/quarkusio/quarkus/discussions/53093)";
    private static final String CHAPPIE = "This working group is now complete. For those interested in the next phase, "
            + "we've kicked off the Quarkus Dev Start follow-up group here: " + LINK + ". See you there!";
    private static final String TRANSLATED = "Este grupo terminou. Continue aqui: " + LINK + "\n";
    private static final Instant DATE = Instant.parse("2026-04-15T00:00:00Z");
    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("working-groups-po4a-");
        try {
            test(temporary);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void test(Path temporary) throws Exception {
        String po4a = System.getenv().getOrDefault("PO4A_TRANSLATE", "po4a-translate");
        Result version = run(po4a, "--version");
        check(version.code() == 0, version.output());
        System.out.println(version.output().lines().findFirst().orElseThrow());

        Path po = temporary.resolve("pt.po");
        Files.writeString(po, """
                msgid ""
                msgstr ""
                "Content-Type: text/plain; charset=UTF-8\\n"
                "Language: pt_BR\\n"

                msgid %s
                msgstr %s
                """.formatted(poString(CHAPPIE + "\n"), poString(TRANSLATED)));

        // This must fail with the actual po4a bug, rather than silently passing on an older version.
        Path original = temporary.resolve("original.yaml");
        Files.writeString(original, "last-update: |\n  " + CHAPPIE + "\n");
        check(((Map<?, ?>) YAML.load(Files.readString(original))).get("last-update").equals(CHAPPIE + "\n"),
                "The original example must be valid YAML");
        Result failed = translate(po4a, original, po, temporary.resolve("original-pt.yaml"));
        check(failed.code() == 255 && failed.output().contains("Inline lists and dictionaries"),
                "Expected the original po4a failure (use po4a 0.74 or another affected version): " + failed);

        String templateSource = Files.readString(Path.of("templates/wg.yaml.template"));
        Engine engine = Engine.builder().addDefaults().addValueResolver(new ReflectionValueResolver())
                .addValueResolver(ValueResolvers.rawResolver()).build();
        Template template = engine.parse(templateSource);
        main.Board chappie = board("Chappie", CHAPPIE);
        String generated = render(template, chappie);
        check(generated.equals(render(template, chappie)), "Consecutive generations must be byte-identical");
        check(lastUpdate(generated).equals(CHAPPIE + "\n"), "Chappie text and link must be preserved exactly");

        Path corrected = temporary.resolve("corrected.yaml");
        Path translated = temporary.resolve("corrected-pt.yaml");
        Files.writeString(corrected, generated);
        Result passed = translate(po4a, corrected, po, translated);
        check(passed.code() == 0, "Generated data must pass po4a: " + passed.output());
        check(lastUpdate(Files.readString(translated)).equals(TRANSLATED), "The PO translation must be applied");

        // Safe values must retain the original template's output, including whitespace.
        Template oldStyle = engine.parse(templateSource.replace("last-update: {board.getLastUpdateYaml().raw}",
                "last-update: |\n        {board.getIndentedLastUpdate().raw}"));
        check(YAML.load(render(oldStyle, chappie)).equals(YAML.load(generated)),
                "Changing scalar style must preserve the entire group schema and data");
        for (String body : List.of("An ordinary update.", "First line\n\nSecond line", "  Text\n  Indented\n")) {
            main.Board ordinary = board("Ordinary", body);
            check(render(template, ordinary).equals(render(oldStyle, ordinary)),
                    "Safe updates must retain their original bytes");
        }

        List<String> bodies = List.of(
                "Details: {key: value}",
                "Both: [link](https://example.com) and: {value}",
                "Quoted \"text\", backslash \\ and literal \\x3a: [link](https://example.com/path?q=x%3A%20[y])",
                "Code `key: [value]`\n\n```yaml\nkey: {value}\n```",
                "Parágrafo com ação e emoji 🚀: [link](https://example.com)",
                "First: [link](https://example.com)\r\n\r\n  Indented\rLast",
                "Tabs\tand spacing: [link](https://example.com)");
        int index = 0;
        for (String body : bodies) {
            String rendered = render(template, board("Escaping", body));
            String expected = body.replace("\r\n", "\n").replace('\r', '\n').trim() + "\n";
            check(lastUpdate(rendered).equals(expected), "Escaping must preserve the decoded update: " + body);
            Path sample = temporary.resolve("escaping-" + index++ + ".yaml");
            Files.writeString(sample, rendered);
            Path roundTrip = temporary.resolve("escaping-pt.yaml");
            Result result = translate(po4a, sample, po, roundTrip);
            check(result.code() == 0, "Escaping fixture must pass po4a: " + result.output());
            check(lastUpdate(Files.readString(roundTrip)).equals(expected),
                    "po4a must preserve untranslated code, URLs and escapes: " + body);
        }

        String originalHtml = HtmlRenderer.builder().build().render(Parser.builder().build().parse(CHAPPIE + "\n"));
        String generatedHtml = HtmlRenderer.builder().build().render(Parser.builder().build().parse(lastUpdate(generated)));
        check(originalHtml.equals(generatedHtml), "Markdown rendering must preserve the text and discussion link");

        Path data = Path.of("../_data/wg.yaml");
        Result dataResult = translate(po4a, data, po, temporary.resolve("wg-pt.yaml"));
        check(dataResult.code() == 0, "The checked-in wg.yaml must also pass po4a: " + dataResult.output());
        System.out.println("PASS: original failure, real template, PO application, escaping, Markdown and repeat generation");
    }

    private static main.Board board(String title, String body) {
        return new main.Board("fixture", "quarkusio", title, "Fixture description", "Fixture readme",
                "https://github.com/orgs/quarkusio/projects/1", DATE,
                new ArrayList<>(List.of(new main.Update("update", body, "", "COMPLETE", DATE))));
    }

    private static String render(Template template, main.Board board) {
        return template.data("boards", List.of(board)).render();
    }

    private static String lastUpdate(String document) {
        Map<?, ?> root = YAML.load(document);
        Map<?, ?> group = (Map<?, ?>) ((List<?>) root.get("working-groups")).getFirst();
        return (String) group.get("last-update");
    }

    private static Result translate(String po4a, Path source, Path po, Path output) throws Exception {
        return run(po4a, "-f", "yaml", "-m", source.toString(), "-p", po.toString(), "-l", output.toString(),
                "-k", "0", "-M", "UTF-8", "-L", "UTF-8");
    }

    private static Result run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }

    private static String poString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Result(int code, String output) {
    }
}
