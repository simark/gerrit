load("@rules_java//java:defs.bzl", "java_library")

java_library(
    name = "openid",
    srcs = glob(["**/*.java"]),
    resource_strip_prefix = "resources",
    resources = ["//resources/com/google/gerrit/httpd/auth/openid"],
    visibility = ["//visibility:public"],
    deps = [
        # We want all these deps to be provided_deps
        "//java/com/google/gerrit/common:annotations",
        "//java/com/google/gerrit/common:server",
        "//java/com/google/gerrit/exceptions",
        "//java/com/google/gerrit/extensions:api",
        "//java/com/google/gerrit/httpd",
        "//java/com/google/gerrit/reviewdb:server",
        "//java/com/google/gerrit/util/http",
        "//java/com/google/gerrit/server",
        "//java/com/google/gerrit/server/audit",
        "//lib:guava",
        "//java/com/google/gwtorm",
        "//lib:servlet-api-3_1",
        "//lib/commons:codec",
        "//lib/flogger:api",
        "//lib/guice",
        "//lib/guice:guice-servlet",
        "//lib/jgit/org.eclipse.jgit:jgit",
        "//lib/openid:consumer",
    ],
)
