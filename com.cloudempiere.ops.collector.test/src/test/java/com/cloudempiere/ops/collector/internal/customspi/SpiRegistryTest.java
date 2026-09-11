/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Sanity checks on the extraction-work SPI register (SPI-CALL-SITE-CONVENTION.md §5). These don't
 * re-verify the catalog's content against the doc — they lock down basic data-integrity invariants
 * so a future hand-edit can't silently corrupt the register (a duplicate/blank entry would make
 * {@link CustomSpiHealthSource} either double-count or look up an empty class name).
 */
class SpiRegistryTest {

	@Test
	void has_exactly_83_vetted_seams() {
		// SPI-CALL-SITE-CONVENTION.md §5 — the vetted count as of this register's creation. See the
		// SpiRegistry class javadoc "Known gap" section for the ~73 additional Core.java accessors
		// deliberately NOT included here pending triage.
		assertEquals(83, SpiRegistry.all().size());
	}

	@Test
	void no_duplicate_interface_fqcns() {
		List<RequiredSpi> all = SpiRegistry.all();
		Set<String> seen = new HashSet<>();
		for (RequiredSpi seam : all) {
			assertTrue(seen.add(seam.interfaceFqcn()), "duplicate interfaceFqcn: " + seam.interfaceFqcn());
		}
	}

	@Test
	void no_duplicate_names() {
		List<RequiredSpi> all = SpiRegistry.all();
		Set<String> seen = new HashSet<>();
		for (RequiredSpi seam : all) {
			assertTrue(seen.add(seam.name()), "duplicate seam name: " + seam.name());
		}
	}

	@Test
	void every_entry_has_a_non_blank_name_fqcn_and_accessor() {
		for (RequiredSpi seam : SpiRegistry.all()) {
			assertFalse(isBlank(seam.name()), "blank name");
			assertFalse(isBlank(seam.interfaceFqcn()), "blank interfaceFqcn for " + seam.name());
			assertFalse(isBlank(seam.accessorName()), "blank accessorName for " + seam.name());
			assertTrue(seam.coreFiles() != null, "null coreFiles for " + seam.name() + " (use List.of())");
		}
	}

	@Test
	void every_interface_fqcn_looks_like_a_java_class_name() {
		for (RequiredSpi seam : SpiRegistry.all()) {
			String fqcn = seam.interfaceFqcn();
			assertTrue(fqcn.contains("."), "not fully-qualified: " + fqcn);
			String simpleName = fqcn.substring(fqcn.lastIndexOf('.') + 1);
			assertEquals(seam.name(), simpleName, "simple name mismatch for " + fqcn);
		}
	}

	@Test
	void exactly_one_seam_is_verified_optional() {
		// Confirmed 2026-09-11 by grepping every repo for "implements IDunningPersonResolver" and
		// finding zero matches — the only one of the 83 with no implementer anywhere in the codebase.
		// See SpiRegistry's "Verified-optional entries" javadoc before marking anything else optional.
		long optionalCount = SpiRegistry.all().stream().filter(RequiredSpi::optional).count();
		assertEquals(1, optionalCount);
		assertTrue(SpiRegistry.all().stream()
				.anyMatch(s -> s.optional() && "IDunningPersonResolver".equals(s.name())));
	}

	@Test
	void all_returns_an_immutable_list() {
		List<RequiredSpi> all = SpiRegistry.all();
		assertTrue(all.getClass().getName().contains("Unmodifiable")
				|| all.getClass().getName().contains("Immutable"),
				"SpiRegistry.all() should be immutable, was " + all.getClass());
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}
}
