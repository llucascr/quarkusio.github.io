# Working-group data generation

`main.java` fetches GitHub project data and renders `templates/wg.yaml.template`.
The scheduled workflow writes `_data/wg.yaml` every three hours.

## Translation compatibility

po4a 0.74 checks each source line for `: [` and `: {` before parsing YAML,
including text inside literal blocks. For affected `last-update` values, the
generator uses a double-quoted YAML scalar and represents the offending colon
as `\x3a`. YAML::Tiny supports this escape; it does not support `\u003a`.
The decoded Markdown, punctuation, links and translation message IDs stay the
same. Other updates retain their existing literal-block representation.

The quoted representation preserves the final newline of the existing `|`
block and normalizes CRLF/CR line endings to LF, as YAML does. No Markdown
rewriting is involved, including for code samples and URLs.

## Regression test

Requires Java 21+, JBang, po4a 0.74 and YAML::Tiny. From the repository root:

```sh
bash working-groups/test.sh
```

To run po4a directly from its source distribution:

```sh
PO4A_TRANSLATE=/path/to/po4a-0.74/po4a-translate \
PERL5LIB=/path/to/po4a-0.74/lib \
bash working-groups/test.sh
```

The test compiles the real generator with JBang integrations disabled. It uses
the real model and template without starting Quarkus or requiring a GitHub token.
It reproduces the original po4a failure, applies a Portuguese PO fixture to the
generated YAML, checks escaping and Markdown output, compares two consecutive
generations, and validates the entire checked-in `_data/wg.yaml` with po4a.
It also verifies that ordinary updates produce the original bytes.

CI pins po4a 0.74 with an archive checksum because older versions, including
0.69, do not have the problematic pre-validation. The same tests run for PRs
and after synchronization, before committing or pushing generated data.
