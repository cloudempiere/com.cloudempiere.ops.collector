# com.cloudempiere.ops.collector.test

Standalone JUnit 5 unit tests for `com.cloudempiere.ops.collector`'s pure-logic and fakeable-OSGi
classes. Deliberately **not** part of the Tycho reactor (JUnit 5 isn't in the iDempiere P2) — a
plain Maven module that depends on the *built* bundle jar, not a Tycho project.

## Running

```bash
cd <repo-root>/com.cloudempiere.ops.collector

# 1. install the parent pom (only needed once, or after a pom.xml change)
mvn -f com.cloudempiere.ops.collector.parent/pom.xml install -N

# 2. build + install the actual bundle jars locally (skips tests, full reactor)
mvn install -Pcloudempiere-local -DskipTests

# 3. run this test module
mvn -f com.cloudempiere.ops.collector.test/pom.xml test
```

Steps 1–2 only need re-running after a change to production code or `pom.xml`s elsewhere in the
reactor; once the bundle jar is installed, iterate on step 3 alone.

## What's covered

- `SpiRegistryTest`, `SpiTransitionTrackerTest`, `SeverityClassifierTest`, `OpsEventTest`,
  `PoolStatsShimTest` — pure logic, no OSGi types involved at all.
- `CustomSpiHealthSourceTest` — the one class here that actually touches `org.osgi.framework.*`
  (`BundleContext`/`ServiceReference`), faked with Mockito rather than a real framework. See the
  two non-obvious things this needed, below.

## Two things that look odd here on purpose

- **`org.osgi:org.osgi.core` test dependency.** `BundleContext`/`ServiceReference` resolve at
  *compile* time transitively through the bundle jar's own dependency metadata, but aren't on the
  Surefire *runtime* classpath without this — this module was originally built only for OSGi-free
  tests, `CustomSpiHealthSourceTest` is the first to need real OSGi API types to mock against.
  This artifact is interfaces only, no framework implementation.
- **`src/test/java/org/compiere/util/CLogger.java`** is a **test-only stub**, not a mistake. Real
  `org.compiere.util.CLogger` lives in iDempiere core (`org.adempiere.base`), which this module
  deliberately doesn't depend on. `EventDispatcher`'s static initializer calls
  `CLogger.getCLogger(...)` unconditionally, and even a Mockito mock of `EventDispatcher` still
  triggers that initializer — `Unsafe.allocateInstance` (used under the hood by Mockito's
  Objenesis-based instantiation) skips the constructor but is documented to still run class
  initialization. Since `EventDispatcher`'s methods are mocked rather than actually invoked in
  tests, the real logging body never runs — this stub only needs to exist so the classloader can
  resolve the one static factory call, nothing more.
