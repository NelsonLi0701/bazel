#!/bin/bash
#
# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Test rule transition can inspect configurable attribute.

# --- begin runfiles.bash initialization ---
# Copy-pasted from Bazel's Bash runfiles library (tools/bash/runfiles/runfiles.bash).
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

function set_up() {
  create_new_workspace
}

function create_transitions() {
  local pkg="${1}"
  mkdir -p "${pkg}"
  cat > "${pkg}/def.bzl" <<EOF

load("//third_party/bazel_skylib/rules:common_settings.bzl", "BuildSettingInfo")

example_package = "${pkg}"

def _transition_impl(settings, attr):
    if getattr(attr, "apply_transition") and settings["//%s:transition_input_flag" % example_package]:
        return {"//%s:transition_output_flag" % example_package: True}
    return {"//%s:transition_output_flag" % example_package: False}

example_transition = transition(
    implementation = _transition_impl,
    inputs = ["//%s:transition_input_flag" % example_package],
    outputs = ["//%s:transition_output_flag" % example_package],
)

def _rule_impl(ctx):
    print("Flag value for %s: %s" % (
        ctx.label.name,
        ctx.attr._transition_output_flag[BuildSettingInfo].value,
    ))

transition_attached = rule(
    implementation = _rule_impl,
    cfg = example_transition,
    attrs = {
        "apply_transition": attr.bool(default = False),
        "deps": attr.label_list(),
        "_transition_output_flag": attr.label(default = "//%s:transition_output_flag" % example_package),
        "_allowlist_function_transition": attr.label(
            default = "//tools/allowlists/function_transition_allowlist:function_transition_allowlist",
        ),
    },
)

transition_not_attached = rule(
    implementation = _rule_impl,
    attrs = {
        "deps": attr.label_list(),
        "_transition_output_flag": attr.label(default = "//%s:transition_output_flag" % example_package),
    },
)
EOF
}

function create_rules_with_incoming_transition_and_selects() {
  local pkg="${1}"
  mkdir -p "${pkg}"
  cat > "${pkg}/BUILD" <<EOF
load(
    "//${pkg}:def.bzl",
    "transition_attached",
    "transition_not_attached",
)
load("//third_party/bazel_skylib/rules:common_settings.bzl", "bool_flag")

bool_flag(
    name = "transition_input_flag",
    build_setting_default = True,
)

bool_flag(
    name = "transition_output_flag",
    build_setting_default = False,
)

config_setting(
    name = "select_setting",
    flag_values = {":transition_input_flag": "True"},
)

# All should print "False" if
# "--no//${pkg}:transition_input_flag" is
# specified on the command line

# bazel build :top_level will print the results for all of the targets below

transition_attached(
    name = "top_level",
    apply_transition = select({
        ":select_setting": True,
        "//conditions:default": False,
    }),
    deps = [
        ":transition_attached_dep",
        ":transition_not_attached_dep",
    ],
)

# Should print "False"
transition_attached(
    apply_transition = False,
    name = "transition_attached_dep",
    deps = [
        ":transition_not_attached_dep_of_dep",
    ],
)

# Should print "True" when building top_level, "False" otherwise
transition_not_attached(
    name = "transition_not_attached_dep",
)

# Should print "False"
transition_not_attached(
    name = "transition_not_attached_dep_of_dep",
)
EOF
}

function test_rule_transition_can_inspect_configure_attributes(){
  local -r pkg="${FUNCNAME[0]}"
  create_transitions "${pkg}"
  create_rules_with_incoming_transition_and_selects "${pkg}"

  bazel build "//${pkg}:top_level" &> $TEST_log || fail "Build failed"

  expect_log 'Flag value for transition_not_attached_dep: True'
  expect_log 'Flag value for transition_not_attached_dep_of_dep: False'
  expect_log 'Flag value for transition_attached_dep: False'
  expect_log 'Flag value for top_level: True'
}

function test_rule_transition_can_inspect_configure_attributes_with_flag(){
  local -r pkg="${FUNCNAME[0]}"

  create_transitions "${pkg}"
  create_rules_with_incoming_transition_and_selects "${pkg}"

  bazel build --no//${pkg}:transition_input_flag "//${pkg}:top_level" &> $TEST_log || fail "Build failed"

  expect_log 'Flag value for transition_not_attached_dep: False'
  expect_log 'Flag value for transition_not_attached_dep_of_dep: False'
  expect_log 'Flag value for transition_attached_dep: False'
  expect_log 'Flag value for top_level: False'
}

function test_rule_transition_can_not_inspect_configure_attribute() {
  local -r pkg="${FUNCNAME[0]}"

  # create transition definition
  mkdir -p "${pkg}"
  cat > "${pkg}/def.bzl" <<EOF

load("//third_party/bazel_skylib/rules:common_settings.bzl", "BuildSettingInfo")

example_package = "${pkg}"

def _transition_impl(settings, attr):
    if getattr(attr, "apply_transition") and settings["//%s:transition_input_flag" % example_package]:
        return {"//%s:transition_output_flag" % example_package: True}
    return {
      "//%s:transition_output_flag" % example_package: False,
      "//%s:transition_input_flag" % example_package: False
    }

example_transition = transition(
    implementation = _transition_impl,
    inputs = ["//%s:transition_input_flag" % example_package],
    outputs = [
      "//%s:transition_output_flag" % example_package,
      "//%s:transition_input_flag" % example_package,
    ],
)

def _rule_impl(ctx):
    print("Flag value for %s: %s" % (
        ctx.label.name,
        ctx.attr._transition_output_flag[BuildSettingInfo].value,
    ))

transition_attached = rule(
    implementation = _rule_impl,
    cfg = example_transition,
    attrs = {
        "apply_transition": attr.bool(default = False),
        "deps": attr.label_list(),
        "_transition_output_flag": attr.label(default = "//%s:transition_output_flag" % example_package),
        "_allowlist_function_transition": attr.label(
            default = "//tools/allowlists/function_transition_allowlist:function_transition_allowlist",
        ),
    },
)
EOF

  # create rules with transition attached
  cat > "${pkg}/BUILD" <<EOF
load(
    "//${pkg}:def.bzl",
    "transition_attached",
)
load("//third_party/bazel_skylib/rules:common_settings.bzl", "bool_flag")

bool_flag(
    name = "transition_input_flag",
    build_setting_default = True,
)

bool_flag(
    name = "transition_output_flag",
    build_setting_default = False,
)

config_setting(
    name = "select_setting",
    flag_values = {":transition_input_flag": "True"},
)

# All should print "False" if
# "--no//${pkg}:transition_input_flag" is
# specified on the command line

# bazel build :top_level will print the results for all of the targets below

transition_attached(
    name = "top_level",
    apply_transition = select({
        ":select_setting": True,
        "//conditions:default": False,
    }),
)
EOF
  bazel build "//${pkg}:top_level" &> $TEST_log && fail "Build did NOT complete successfully"
  expect_log "No attribute 'apply_transition'. Either this attribute does not exist for this rule or the attribute was not resolved because it is set by a select that reads flags the transition may set."
}

run_suite "rule transition tests"
