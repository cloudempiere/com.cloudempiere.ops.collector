# SPEC — Custom-SPI health check (extraction-work seam register + readiness gate)

> **Status:** **IMPLEMENTED** 2026-09-10 — `SpiRegistry` (83 seams), `SpiTransitionTracker`,
> `CustomSpiHealthSource`, `OpsStatusProvider` wired as the first gating source, `SeverityClassifier`
> case, unit tests. Full reactor build green (`mvn install -Pcloudempiere-local -DskipTests`, 10/10
> modules) and standalone test module green (28/28, incl. 11 new: `SpiRegistryTest` ×6,
> `SpiTransitionTrackerTest` ×5) — verified 2026-09-10. `OSGI-INF/*.xml` descriptors confirmed present
> in the built JAR. **Code review (2026-09-11) caught and fixed two real bugs before any live
> deploy:** (1) `CustomSpiHealthSource.xml` was missing its `<service>` block — the component
> never registered as a service, so `OpsStatusProvider`'s MANDATORY reference to it could never
> satisfy, which would have kept `OpsStatusProvider` itself from ever activating (i.e. `/readyz`,
> `/livez`, `/startupz` all down, on every deploy); (2) `SpiTransitionTracker`'s internal set was
> mutated by the poller thread and read unsynchronized from HTTP request threads — both methods
> are now `synchronized`. Both fixed, rebuilt, retested (28/28 green). **Live-verified 2026-09-11**
> against a real running instance: caught 8 genuinely-missing seams on first boot, all traced to one
> missing bundle (`org.idempiere.banking` not in the launch config's bundle list — fixed), and one
> false alarm (`IDunningPersonResolver`, now `RequiredSpi.optional(...)`, see §9). 29/29 tests green
> after that change. This is the health check's first real catch, end to end.
> **Scope:** ADR-001 §4 (event sources) / §5 (severity), extended with a new — deliberately gating —
> readiness contributor. Related: `cloudempiere-workspace/docs/architecture/plugin_extraction/
> SPI-CALL-SITE-CONVENTION.md` (the register's source of truth).
> **Author:** CloudEmpiere

## 1. Motivation

The extraction work (moving CloudEmpiere business logic out of upstream `core/` iDempiere files)
introduced `Core.getXxx()` seam interfaces (`org.cloudempiere.base.Core`, bundle
`org.adempiere.base`), each resolved via `Service.locator()` — an OSGi service lookup. Per the
2026-08-06 "Option B" decision in `SPI-CALL-SITE-CONVENTION.md`, an accessor returns `null` when no
plugin implementation is registered, and the core call site falls back inline to the vanilla/
community behavior that upstream `release-10` had there. That fallback is deliberate — it keeps
`git subtree pull` merge conflicts readable, showing both paths on one screen — but it is **not** an
acceptable production steady-state: a plugin that fails to activate (bad OSGi wiring, a missing
dependency, a packaging bug) degrades **silently**, with no signal anywhere that CloudEmpiere
business logic quietly stopped applying and vanilla iDempiere behavior took over instead.

This is the extraction-work analogue of ADR-001's "ACTIVE is a lie" thesis (§1): the bundle can be
`ACTIVE`, the DS component `SATISFIED`, and the *specific* service a core call site depends on can
still be absent — none of which container health, `ss`, or even this collector's own generic
`DSComponentSource` poller reliably catches (that poller is explicitly non-gating for exactly this
kind of ambiguity — see `SeverityClassifier`'s `plugin.component.unsatisfied` → AUDIT demotion,
because "unsatisfied" is too often benign/tier-irrelevant to page on). What's missing is a check that
knows *which* specific services are extraction-work-mandatory, and treats any of them being absent as
a defect, not noise.

## 2. Goals / Non-goals

**Goals**
- A **register** of the SPI seams introduced by the extraction work, so "is CloudEmpiere business
  logic active" is a machine-checkable list, not tribal knowledge.
- A **health check** that, on startup and continuously, verifies every registered seam has a real
  implementation registered in the OSGi service registry.
- Treat a missing required seam as **"the system should not run"**: `/readyz` never returns healthy
  while any is missing (never-ready, not a hard boot-abort — the JVM stays up for log/console
  inspection).
- Structured, transition-only events (PANIC on newly-missing, AUDIT on recovered/all-active) so the
  signal pages once, not on every poll.

**Non-goals**
- NOT a register of every extraction mechanism. `IModelFactory` subclass overrides (e.g.
  `CldeMOrderLine`), `ModelValidator`-based abort logic, and `AbstractEventHandler` side effects are
  different registration mechanisms with different failure signatures — out of scope for this pass.
- NOT exhaustive over every `Core.getXxx()` accessor. See §9 "Open questions" — `Core.java` has 156
  such accessors; only 83 are vetted extraction-work seams as of this SPEC. The other ~73 need
  per-item triage before they can safely join a "mandatory everywhere" registry.
- NOT per-client/per-deployment optionality. Every registered seam is required on every
  CloudEmpiere deployment for this pass — no "optional module" flag on a `RequiredSpi` entry (yet).
- NOT a generalization of the "gate `/readyz`" pattern to other sources. This is a deliberate,
  called-out exception to this collector's non-gating-by-default policy (`RuntimeHealthSource` §7),
  not a precedent — see §6.

## 3. What "the register" is built from

`org.cloudempiere.base.Core` (bundle `org.adempiere.base`) has 156 `public static I... getXxx()`
accessors. `SPI-CALL-SITE-CONVENTION.md` §5 ("Seam inventory") catalogs 83 of them as extraction-work
seams, each with its interface, `Core` accessor name, and the `core/` files that call it. Every
accessor follows the same shape:

```java
public static IWmsStorageOwnerPolicy getWmsStorageOwnerPolicy() {
	IServiceHolder<IWmsStorageOwnerPolicy> serviceHolder = Service.locator().locate(IWmsStorageOwnerPolicy.class);
	if (serviceHolder != null && serviceHolder.getService() != null)
		return serviceHolder.getService();
	return null; // or, for accessors not yet migrated to Option B: return DefaultXxx.getInstance();
}
```

`Service.locator().locate(X.class)` (`org/adempiere/base/ds/DynamicServiceLocator.java`) is a
straight OSGi `ServiceTracker` over the bundle's `BundleContext` — not a custom registry. This
matters for the health check design (§4): calling `Core.getXxx()` itself would be masked, for any
accessor not yet migrated to Option B, by its `Default*` fallback — the accessor would keep returning
a non-null (vanilla-behavior) instance even with zero plugin registered. The health check therefore
queries the OSGi service registry directly, bypassing `Core` entirely.

All 83 interfaces live in the same single bundle (`org.adempiere.base`), spread across ~30
`*.spi`/`.core` packages, each individually `Export-Package`d.

## 4. Component design

```
 SpiRegistry (83 seams)
        │
        ▼
 CustomSpiHealthSource ──poll 30s──▶ OSGi service registry
        │                            (getServiceReferences)
        ▼
 SpiTransitionTracker (missing? new? recovered?)
        │
        ▼
 EventDispatcher ──▶ ops.customspi.missing (PANIC)
        │
        ▼
 OpsStatusProvider.isReady() ──▶ /readyz  (503 if any missing)
```

```
internal/customspi/RequiredSpi              plain immutable holder: name, interfaceFqcn (String),
                                              accessorName, coreFiles — JavaSE-11 target, not a record
internal/customspi/SpiRegistry               static List<RequiredSpi>, the 83 vetted entries
internal/customspi/SpiTransitionTracker      pure emit-once-per-transition bookkeeping (no OSGi dep)
internal/customspi/CustomSpiHealthSource     DS @Component(immediate=true)
  @Reference(MANDATORY) EventDispatcher dispatcher
  BundleContext ctx                          captured in @Activate, no compile-time Import-Package
  ScheduledExecutorService poller            daemon, scheduleWithFixedDelay
                                              (no DB-readiness Condition gate — pure OSGi lookup,
                                              zero SQL, unlike RuntimeHealthSource's Trx/pool scan)
  poll cadence: OPS_COLLECTOR_CUSTOMSPI_POLL_SECONDS (default 30 — tighter than the other 60s
                pollers, since this result directly gates /readyz)
```

Interface FQCNs are stored as **Strings**, not `Class<?>` — deliberate, mirrors the existing
no-compile-dependency shim convention already used for OTel (`internal/OtelContext`) and Hikari
(`internal/PoolStatsShim`). `BundleContext.getServiceReferences(String clazz, String filter)` takes a
class name as a string, so this module never needs `Import-Package` on the ~30 packages the 83
interfaces are spread across — it only imports `org.osgi.framework`, already a dependency.

Per poll: for every `SpiRegistry` entry, `ctx.getServiceReferences(interfaceFqcn, null)` — empty or
null means missing. The resulting missing-name set is fed to `SpiTransitionTracker`, which returns
what changed since the previous poll (newly-missing, newly-recovered, whether the missing set just
became empty) — kept as a separate pure class specifically so this logic is unit-testable without a
live `BundleContext` (this test module exercises only pure-logic classes; see
`com.cloudempiere.ops.collector.test`'s pom.xml).

State exposed for the readiness gate / status provider:
```java
public boolean      isAllRequiredActive();   // fail-closed: false until the first poll completes
public Set<String>  missingSpiNames();       // sorted, empty (never null) once armed
```

## 5. Event taxonomy + severity

| event_type | Severity | Fires when | details |
|---|---|---|---|
| `ops.customspi.missing` | **PANIC** | a required seam transitions from present/unarmed to missing | `subject={kind:"custom_spi", interface, accessor}`, `details.core_files` |
| `ops.customspi.recovered` | AUDIT (default classification) | a previously-missing seam is registered again | `subject={kind:"custom_spi", interface, accessor}` |
| `ops.customspi.all_active` | AUDIT (default classification) | the missing set becomes empty (recovery, or a clean first poll) | `details.required_count` |

`ops.customspi.missing` is the only new explicit `SeverityClassifier` case — PANIC, alongside
`ops.runtime.trx_leak_storm` / `ops.runtime.pool_exhausted`, all framework/fleet-scope failures.
`recovered`/`all_active` rely on the classifier's existing AUDIT default, the same convention
`plugin.lifecycle.recovered` already uses — no explicit case needed.

## 6. Readiness interaction — the one deliberately *gating* source

Every other source in this collector is explicitly **non-gating**: `RuntimeHealthSource` §7 keeps
`/readyz` strictly A/B/C-infra by default; `SeverityClassifier` demotes generic
`plugin.component.unsatisfied` to AUDIT specifically because it is "too often benign to page on... a
blanket-PANIC... caused an alert flood." This source inverts that policy on purpose:

```java
// OpsStatusProvider
public boolean isReady() {
	return boot.isReady() && !shutdown.isDraining() && customSpi.isAllRequiredActive();
}
```

The extraction work's whole premise is that these 83 seams are mandatory everywhere — there is no
"tier where this seam doesn't apply" the way an orderstatus/wcs/dms model factory can legitimately be
irrelevant on a given tier. A missing one is never "this tier doesn't need it," so gating is correct
here in a way it would not be for the generic DS poller. `isAllRequiredActive()` is fail-closed: it
returns `false` until at least one poll has completed, so the small window between bundle activation
and the first scheduled poll reports unready rather than a false-positive ready.

`GET /api/v1/ops/plugins` gains a `custom_spi` block (`required`, `active`, `missing`, `all_active`)
so an operator can see *which* seam(s) are missing without a separate endpoint.

## 7. Env knobs

| Knob | Default | Meaning |
|---|---|---|
| `OPS_COLLECTOR_CUSTOMSPI_POLL_SECONDS` | 30 | poll cadence — tighter than the other sources' 60s because this result gates `/readyz` |

## 8. Deliverables

1. `internal/customspi/RequiredSpi.java`, `SpiRegistry.java` (83 entries), `SpiTransitionTracker.java`,
   `CustomSpiHealthSource.java` (+ hand-authored
   `OSGI-INF/com.cloudempiere.ops.collector.internal.customspi.CustomSpiHealthSource.xml` — DS
   descriptors are NOT generated here, `CLAUDE.md` "Critical gotchas"; already covered by the
   existing `OSGI-INF/` wildcard in `build.properties`'s `bin.includes`, no change needed there).
2. `OpsStatusProvider` — new mandatory `customSpi` reference, `isReady()` gated, `plugins()` extended
   with the `custom_spi` block; `OSGI-INF/…OpsStatusProvider.xml` updated with the new reference.
3. `SeverityClassifier` — one new `case` (`ops.customspi.missing` → PANIC).
4. `…ops.collector.test` — `SpiRegistryTest` (data-integrity invariants: exactly 83 entries, no
   duplicate FQCN/name, no blank fields, immutability), `SpiTransitionTrackerTest` (transition-only
   emission, fail-closed-until-armed semantics), `SeverityClassifierTest` extended.
5. Env knob documented in this plugin's `CLAUDE.md`.

## 9. Open questions

- **The 73-accessor reconciliation gap.** `Core.java` has 156 `I.../getXxx()` accessors; only the 83
  in `SPI-CALL-SITE-CONVENTION.md` §5 are in this registry. The other ~73 are mostly CLDE-named
  (`ICostDetailFactory`, `IInvoiceAllocationSupport`, `IAccessSqlTableInfoPolicy`, …) but at least one
  (`IDMS`) looks like a pre-existing, legitimately-optional upstream iDempiere extension point rather
  than a CLDE-mandatory seam. Sorting these into "belongs in the register" vs. "correctly excluded"
  needs per-item triage against upstream/git history — a follow-up task, tracked in both this SPEC and
  `SPI-CALL-SITE-CONVENTION.md` §5's own notes, not resolved here.
  - The first pass through that gap: `docs/architecture/plugin_extraction/SPI-CALL-SITE-CONVENTION.md`
    §5 needs the 73 either added (with core-caller counts, like the existing 83) or explicitly
    excluded with a reason, per its own §8 "Going forward" process — that document, not this SPEC,
    should stay the single catalog of *which accessors exist*; this SPEC/registry decides which of
    them are checked at runtime.
- **Per-deployment optionality — first case landed 2026-09-11.** `IDunningPersonResolver` is now
  `RequiredSpi.optional(...)`: confirmed by grepping every repo for `implements
  IDunningPersonResolver` and finding zero matches — the only one of the 83 with no implementer
  anywhere in the codebase, i.e. not a missing-from-this-deployment bug but a seam no CLDE client has
  ever needed. This was distinguished, live, from a real gap the same session: `IPaymentCompletionGuard`
  and 7 sibling banking seams initially looked identical (null accessor, graceful fallback, missing
  from one local instance's `scr:list`) but turned out to have real implementations in
  `org.idempiere.banking` — that plugin just wasn't in the launch config's bundle list. The
  distinguishing test is NOT "does it have a null-safe fallback" (mandatory for all 83, including the
  banking ones) — it's "does grepping the whole codebase for `implements <Interface>` return zero
  results." Re-run that grep per-seam before marking anything else optional; do not generalize from
  the fallback shape alone. If a future licensed-module scenario needs conditional-by-deployment
  (rather than confirmed-nowhere) optionality, that's a different flag and still open.
- **`IModelFactory`/`ModelValidator`/`EventHandler` coverage.** These extraction mechanisms have no
  equivalent health signal today. Explicitly out of scope for this pass (confirmed with the user); a
  natural Phase 2 if the same silent-fallback risk turns out to apply there too.
- **OSGi integration test.** Like `RuntimeHealthSource`, this only has unit coverage on the pure
  logic; a Pax Exam test that actually stops a bundle providing a registered seam and asserts
  `/readyz` flips is still TODO, per ADR-001 §4.15's existing note on OSGi integration tests.
