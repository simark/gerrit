load("@rules_java//java:defs.bzl", "java_library")
load("//javatests/com/google/gerrit/acceptance:tests.bzl", "acceptance_tests")

[acceptance_tests(
    srcs = [f],
    group = f[:f.index(".")],
    labels = ["git"],
    deps = [
        ":push_for_review",
        ":submodule_util",
        "//lib/commons:lang",
    ],
) for f in glob(["*IT.java"])]

java_library(
    name = "push_for_review",
    testonly = True,
    srcs = ["AbstractPushForReview.java"],
    deps = [
        "//java/com/google/gerrit/acceptance:lib",
        "//java/com/google/gerrit/mail",
    ],
)

java_library(
    name = "submodule_util",
    testonly = True,
    srcs = ["AbstractSubmoduleSubscription.java"],
    deps = ["//java/com/google/gerrit/acceptance:lib"],
)
