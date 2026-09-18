/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.cloudempiere.ops.collector.internal.EventDispatcher;

/**
 * Tests {@link CustomSpiHealthSource} itself — the one piece of SPEC-custom-spi-health with no
 * coverage yet ({@link SpiRegistryTest} only checks the data, {@link SpiTransitionTrackerTest}
 * only checks the pure transition bookkeeping). Fakes {@link BundleContext} and
 * {@link EventDispatcher} with Mockito, deliberately NOT by subclassing {@code EventDispatcher} -
 * that would trigger its static initializer ({@code CLogger.getCLogger(...)}, an iDempiere core
 * class this OSGi-free test module doesn't have on its classpath). A Mockito mock never runs the
 * mocked class's constructors or static initializers, which is exactly why it works here.
 * <p>
 * {@code dispatcher}/{@code ctx} are private fields normally set by DS/{@code activate(...)}, and
 * {@code poll()}/{@code pollQuietly()} are private — reflection is used deliberately instead of
 * calling {@code activate(...)}, which would also arm a real background poller thread on a real
 * delay; that's exactly what a fast, deterministic unit test needs to avoid.
 */
class CustomSpiHealthSourceTest {

	/** What a mocked {@link EventDispatcher#emit(String, Map, Map)} call recorded. */
	private static final class Recorded {
		final List<String> eventTypes = new ArrayList<>();
		final List<Map<String, Object>> subjects = new ArrayList<>();
	}

	private static EventDispatcher mockDispatcher(Recorded recorded) {
		EventDispatcher dispatcher = mock(EventDispatcher.class);
		doAnswer(invocation -> {
			recorded.eventTypes.add(invocation.getArgument(0));
			recorded.subjects.add(invocation.getArgument(1));
			return null;
		}).when(dispatcher).emit(anyString(), any(), any());
		return dispatcher;
	}

	private static CustomSpiHealthSource newSource(EventDispatcher dispatcher, BundleContext ctx) throws Exception {
		CustomSpiHealthSource source = new CustomSpiHealthSource();
		setField(source, "dispatcher", dispatcher);
		setField(source, "ctx", ctx);
		return source;
	}

	/** Runs exactly one poll cycle synchronously — the same method the real scheduler calls. */
	private static void pollOnce(CustomSpiHealthSource source) throws Exception {
		Method m = CustomSpiHealthSource.class.getDeclaredMethod("pollQuietly");
		m.setAccessible(true);
		m.invoke(source);
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		Field f = CustomSpiHealthSource.class.getDeclaredField(fieldName);
		f.setAccessible(true);
		f.set(target, value);
	}

	/** A BundleContext where every seam resolves except the given (non-optional) FQCNs. */
	private static BundleContext ctxMissing(Set<String> missingFqcns) throws Exception {
		BundleContext ctx = mock(BundleContext.class);
		when(ctx.getServiceReferences(anyString(), isNull())).thenAnswer(invocation -> {
			String fqcn = invocation.getArgument(0);
			if (missingFqcns.contains(fqcn)) return null;
			return new ServiceReference<?>[] { mock(ServiceReference.class) };
		});
		return ctx;
	}

	private static RequiredSpi anyRequiredSeam() {
		return SpiRegistry.all().stream().filter(s -> !s.optional()).findFirst()
				.orElseThrow(() -> new IllegalStateException("registry has no required seams to test with"));
	}

	@Test
	void all_present_reports_ready_and_fires_all_active_once() throws Exception {
		Recorded recorded = new Recorded();
		CustomSpiHealthSource source = newSource(mockDispatcher(recorded), ctxMissing(Set.of()));

		pollOnce(source);

		assertTrue(source.isAllRequiredActive());
		assertTrue(source.missingSpiNames().isEmpty());
		assertTrue(recorded.eventTypes.contains("ops.customspi.all_active"));
		assertFalse(recorded.eventTypes.contains("ops.customspi.missing"));
	}

	@Test
	void one_missing_seam_flips_not_ready_and_emits_missing_with_seam_details() throws Exception {
		RequiredSpi seam = anyRequiredSeam();
		Recorded recorded = new Recorded();
		CustomSpiHealthSource source = newSource(mockDispatcher(recorded), ctxMissing(Set.of(seam.interfaceFqcn())));

		pollOnce(source);

		assertFalse(source.isAllRequiredActive());
		assertEquals(Set.of(seam.name()), source.missingSpiNames());
		assertEquals(1, recorded.eventTypes.stream().filter("ops.customspi.missing"::equals).count());
		Map<String, Object> subject = recorded.subjects.get(recorded.eventTypes.indexOf("ops.customspi.missing"));
		assertEquals("custom_spi", subject.get("kind"));
		assertEquals(seam.interfaceFqcn(), subject.get("interface"));
		assertEquals(seam.accessorName(), subject.get("accessor"));
	}

	@Test
	void before_first_poll_reports_not_ready_even_though_missing_set_starts_empty() throws Exception {
		// Fail-closed guard: isAllRequiredActive() must not read "true" just because the tracker's
		// missing set happens to start empty before any poll has actually run.
		CustomSpiHealthSource source = newSource(mockDispatcher(new Recorded()), ctxMissing(Set.of()));

		assertFalse(source.isAllRequiredActive(), "must be fail-closed before the first poll completes");
	}

	@Test
	void recovery_after_a_missing_poll_fires_recovered_and_all_active() throws Exception {
		RequiredSpi seam = anyRequiredSeam();
		Recorded recorded = new Recorded();
		BundleContext flakyCtx = mock(BundleContext.class);
		Set<String> currentlyMissing = new HashSet<>(Set.of(seam.interfaceFqcn()));
		when(flakyCtx.getServiceReferences(anyString(), isNull())).thenAnswer(invocation -> {
			String fqcn = invocation.getArgument(0);
			if (currentlyMissing.contains(fqcn)) return null;
			return new ServiceReference<?>[] { mock(ServiceReference.class) };
		});
		CustomSpiHealthSource source = newSource(mockDispatcher(recorded), flakyCtx);

		pollOnce(source); // first poll: seam missing
		assertFalse(source.isAllRequiredActive());

		currentlyMissing.clear(); // seam's provider bundle "comes back"
		pollOnce(source);

		assertTrue(source.isAllRequiredActive());
		assertTrue(recorded.eventTypes.contains("ops.customspi.recovered"));
		assertTrue(recorded.eventTypes.contains("ops.customspi.all_active"));
	}

	@Test
	void optional_seam_never_counted_missing_or_gates_readiness() throws Exception {
		RequiredSpi optionalSeam = SpiRegistry.all().stream().filter(RequiredSpi::optional).findFirst().orElse(null);
		assumeTrue(optionalSeam != null, "no optional seam currently in the registry to test with");

		Recorded recorded = new Recorded();
		// optional seam's own interface is "missing" too - must not matter.
		CustomSpiHealthSource source = newSource(mockDispatcher(recorded), ctxMissing(Set.of(optionalSeam.interfaceFqcn())));

		pollOnce(source);

		assertTrue(source.isAllRequiredActive());
		assertFalse(source.missingSpiNames().contains(optionalSeam.name()));
	}
}
