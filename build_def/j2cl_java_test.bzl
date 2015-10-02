"""j2cl_java_test macro

A build macro that uses j2cl_java_library to cross compile Java to JavaScript
and emits closure test suites for all JUnit Tests.
Note: The source attribute must only glob actual JUnit4 test cases. If you need
extra Java files for your tests use a j2cl_java_library and add it as a
dependency.

Here is an example usage:

load("/third_party/java_src/j2cl/build_def/j2cl_java_test", "j2cl_java_test")

j2cl_java_library(
    name = "my_library",
    srcs = ["MyNonTestSource.java"],
)

j2cl_java_test(
    name = "MyTest",
    srcs = ["MyTest.java"],
    deps = [
        ":my_library",
        "//third_party/java/junit",
    ],
)

"""

load(
    "/third_party/java_src/j2cl/build_def/j2cl_java_library",
    "j2cl_java_library")
load("/third_party/java_src/j2cl/build_def/j2cl_util", "get_java_package")
load("/third_party/java_src/j2cl/build_def/j2cl_util", "get_java_root")

J2CL_JAVA_TEST_CLOSURE_DEFS = [
    "--language_in=ECMASCRIPT6",
    "--language_out=ECMASCRIPT5",
    "--jscomp_off=nonStandardJsDocs",
    "--jscomp_off=checkTypes",
    "--jscomp_off=undefinedVars",
    "--export_test_functions=true",
    "--property_renaming=OFF",
    "--strict",
    "--variable_renaming=OFF",
    "--pretty_print",
    "--jscomp_off=lateProvide",
    "--jscomp_off=extraRequire",
    "--jscomp_off=uselessCode",
]

_TEMPLATE = """// THIS IS GENERATED CODE. DO NOT EDIT.
// GENERATED FROM %s
// BY USING %s

package %s;

import com.google.j2cl.junit.J2ClSuiteClasses;

/**
 * J2cl java tests.
 *
 * @author Class generated by build rule j2cl_java_test.
 */
@J2ClSuiteClasses({%s})
public class %s {
}
"""


def _make_full_qualified(package, relative_file_name):
  java_class_name = relative_file_name[: -len(".java")]
  java_class_name = java_class_name.replace("/", ".")
  return package + "." + java_class_name


def _write_file_impl(ctx):
  output = ctx.outputs.out
  ctx.file_action(output=output, content=ctx.attr.content)

_write_file = rule(
    implementation=_write_file_impl,
    attrs={"content": attr.string()},
    outputs={"out": "%{name}.java"},
)


def _generate_suite(name, package, srcs):
  if len(srcs) == 0:
    fail("No srcs defined in rule")
  # print(srcs)
  full_qualified_classes = []
  for src in srcs:
    full_qualified_classes += [_make_full_qualified(package, src) + ".class"]
  BUILD_FILE = "//%s/BUILD (target: %s)" % (package, name),
  java_code = (
      _TEMPLATE %
      (BUILD_FILE, "j2cl_java_test", package,
       ",".join(full_qualified_classes),
       name.title() + "_generated_suite"))
  _write_file(name=name.title() + "_generated_suite", content=java_code)


def j2cl_java_test(**kwargs):
  """Macro for running a JUnit test cross compiled as a web test

     This macro uses the j2cl_java_library macro to transpile test and
     runs an APT on the java_library which outputs JavaScript files for all
     JUnit Test cases.
     This JavaScript is extracted from the jar with a genrule and then fed into
     a js_unit / web_test.
  """

  if not "srcs" in kwargs:
    fail("No srcs defined in rule")

  java_root = get_java_root(PACKAGE_NAME)
  java_package = get_java_package(PACKAGE_NAME)
  base_name = kwargs["name"]

  _generate_suite(base_name, java_package, kwargs["srcs"])

  # add the generated suite to the java_srcs for the java_library so we see
  # it in transpilation
  kwargs["srcs"] = kwargs[
      "srcs"] + [base_name.title() + "_generated_suite.java"]
  if not "deps" in kwargs:
    kwargs["deps"] = []
  kwargs["deps"] = kwargs[
      "deps"] + ["//junit/java/com/google/j2cl/junit:junit_annotations"]

  kwargs["testonly"] = 1

  # Add our APT to java plugins.
  if not "plugins" in kwargs:
    kwargs["plugins"] = []
  kwargs["plugins"] = (
      kwargs["plugins"] + ["//:junit_processor"])

  # path to the jar produced by the java_library rule defined in
  # j2cl_java_library
  out_jar = "blaze-out/host/bin/" + PACKAGE_NAME + "/lib" + base_name + ".jar"

  j2cl_java_library(**kwargs)

  native.genrule(
      name=base_name + "_extract_js",
      srcs=[":" + base_name],
      outs=[base_name + "_extracted_js.js",],
      cmd=("$(location //third_party/unzip:unzip) -p "
           + " " + out_jar + " j2cl_generated_test_suite.js > $@"),
      testonly=1,
      tools=[
          "lib" + base_name + ".jar",
          "//third_party/unzip",
      ],
  )

  tags = []
  if "tags" in kwargs:
    tags = kwargs["tags"]

  # TODO(dankurka): Add support for different browsers
  # TODO(dankurka): Investigate better setup for IE
  native.jsunit_test(
      name=base_name + "_js_test",
      srcs=[":" + base_name + "_extract_js",],
      compile=1,
      compiler="//javascript/tools/jscompiler:head",
      defs=J2CL_JAVA_TEST_CLOSURE_DEFS,
      externs_list=["//javascript/externs:common"],
      deps=[
          ":" + base_name + "_js_library",
          "//javascript/closure/testing:testsuite",
      ],
      jvm_flags=[
          "-Dcom.google.testing.selenium.browser=CHROME_LINUX"
      ],
      data=["//testing/matrix/nativebrowsers/chrome:stable_data",],
      tags=tags,
  )
