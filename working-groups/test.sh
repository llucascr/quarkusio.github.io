#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Compile the generator and exercise its model/template without Quarkus startup or a GitHub token.
jbang run --no-integrations --sources=WorkingGroupsTest.java --main=WorkingGroupsTest main.java
