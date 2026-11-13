#!/usr/bin/env bash
# Run the REAL checked-out Lane X pure Kotlin/JUnit sources without Android or Gradle.
# Never contacts GitHub, creates an APK, starts Android CI or opens user media.
set -euo pipefail

root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
main="$root/app/src/main/java/nl/atlas/gallery/featurepersistence"
tests="$root/app/src/test/java/nl/atlas/gallery/featurepersistence"
for command in javac kotlinc kotlin mktemp; do
  if ! command -v "$command" >/dev/null 2>&1; then
    printf 'Missing offline host prerequisite: %s\n' "$command" >&2
    exit 2
  fi
done

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/org/junit" "$work/classes"
cat > "$work/src/org/junit/Test.java" <<'JAVA'
package org.junit;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
public @interface Test {}
JAVA
cat > "$work/src/org/junit/Assert.java" <<'JAVA'
package org.junit;
import java.util.Arrays;
import java.util.Objects;
public final class Assert {
    private Assert() {}
    public static void assertTrue(boolean value) {
        if (!value) fail("Expected true");
    }
    public static void assertFalse(boolean value) {
        if (value) fail("Expected false");
    }
    public static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) fail("Expected " + expected + ", got " + actual);
    }
    public static void assertEquals(long expected, long actual) {
        if (expected != actual) fail("Expected " + expected + ", got " + actual);
    }
    public static void assertArrayEquals(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) fail("Byte arrays differed");
    }
    public static void fail(String reason) { throw new AssertionError(reason); }
}
JAVA
cat > "$work/src/RunLaneX.kt" <<'KOTLIN'
import nl.atlas.gallery.featurepersistence.KeepReceiptContractTest
import nl.atlas.gallery.featurepersistence.PeopleBackupCodecTest
import nl.atlas.gallery.featurepersistence.PeopleDocumentTransportTest
import nl.atlas.gallery.featurepersistence.PeopleMergePlannerTest
import org.junit.Test
import java.lang.reflect.InvocationTargetException

fun main() {
    val suites = listOf(
        PeopleBackupCodecTest::class.java,
        PeopleMergePlannerTest::class.java,
        KeepReceiptContractTest::class.java,
        PeopleDocumentTransportTest::class.java
    )
    var executed = 0
    val failures = mutableListOf<String>()
    for (suite in suites) {
        val methods = suite.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }
            .sortedBy { it.name }
        require(methods.isNotEmpty()) { "No @Test methods found in ${suite.simpleName}" }
        for (method in methods) {
            try {
                val freshInstance = suite.getDeclaredConstructor().newInstance()
                method.invoke(freshInstance)
                println("PASS ${suite.simpleName}.${method.name}")
            } catch (wrapped: InvocationTargetException) {
                val error = wrapped.targetException
                System.err.println("FAIL ${suite.simpleName}.${method.name}: $error")
                failures.add("${suite.simpleName}.${method.name}: $error")
            }
            executed++
        }
    }
    require(executed >= 36) { "Only $executed methods ran; expected at least the 36 baseline tests" }
    check(failures.isEmpty()) { "${failures.size}/$executed host assertions failed: $failures" }
    println("LANE X REAL SOURCE HOST TESTS: $executed/$executed PASS")
}
KOTLIN

javac -d "$work/classes" "$work/src/org/junit/Test.java" "$work/src/org/junit/Assert.java"
kotlinc \
  "$main/PeopleBackupCodec.kt" \
  "$main/PeopleMergePlanner.kt" \
  "$main/KeepReceiptContract.kt" \
  "$main/PeopleDocumentTransport.kt" \
  "$tests/PeopleBackupCodecTest.kt" \
  "$tests/PeopleMergePlannerTest.kt" \
  "$tests/KeepReceiptContractTest.kt" \
  "$tests/PeopleDocumentTransportTest.kt" \
  "$work/src/RunLaneX.kt" \
  -classpath "$work/classes" -d "$work/classes"
kotlin -J-Xmx512m -classpath "$work/classes" RunLaneXKt
