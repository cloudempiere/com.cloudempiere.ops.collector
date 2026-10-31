/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

/**
 * Unit tests for {@link FactoryProducts}: an interface another scanned interface returns is a
 * factory product and never gates readiness. Cases mirror the two real ones that kept
 * {@code /readyz} red ({@code IDMS}, {@code IPDFFontMapper}).
 */
class FactoryProductsTest {

	private static final String BASE = "com.cloudempiere.base.spi.";
	private static final String PDF = "com.cloudempiere.pdf.spi.";

	private static ScannedSpi seam(String fqcn) {
		return new ScannedSpi(fqcn.substring(fqcn.lastIndexOf('.') + 1), fqcn, "org.adempiere.base", Bundle.ACTIVE,
				false);
	}

	private static ScannedSpi byName(List<ScannedSpi> seams, String name) {
		return seams.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
	}

	@Test
	void product_of_a_scanned_factory_is_marked_and_optional() {
		List<ScannedSpi> marked = FactoryProducts.mark(
				List.of(seam(BASE + "IDMSFactory"), seam(BASE + "IDMS")),
				Map.of(BASE + "IDMSFactory", Set.of(BASE + "IDMS"), BASE + "IDMS", Set.of("java.io.File")));

		ScannedSpi dms = byName(marked, "IDMS");
		assertEquals(BASE + "IDMSFactory", dms.producedBy());
		assertTrue(dms.optional());

		ScannedSpi factory = byName(marked, "IDMSFactory");
		assertNull(factory.producedBy());
		assertFalse(factory.optional(), "the factory itself still gates readiness");
	}

	@Test
	void product_in_another_spi_package_is_marked() {
		List<ScannedSpi> marked = FactoryProducts.mark(
				List.of(seam(BASE + "IPDFFontMapperFactory"), seam(PDF + "IPDFFontMapper")),
				Map.of(BASE + "IPDFFontMapperFactory", Set.of(PDF + "IPDFFontMapper")));

		assertEquals(BASE + "IPDFFontMapperFactory", byName(marked, "IPDFFontMapper").producedBy());
	}

	@Test
	void fluent_self_return_is_not_a_product() {
		List<ScannedSpi> marked = FactoryProducts.mark(List.of(seam(BASE + "IBuilder")),
				Map.of(BASE + "IBuilder", Set.of(BASE + "IBuilder")));

		assertFalse(byName(marked, "IBuilder").optional());
	}

	@Test
	void return_types_outside_the_scan_change_nothing() {
		List<ScannedSpi> seams = List.of(seam(BASE + "IWidgetPolicy"));
		List<ScannedSpi> marked = FactoryProducts.mark(seams,
				Map.of(BASE + "IWidgetPolicy", Set.of("java.lang.String", "org.compiere.model.PO")));

		assertEquals(seams, marked, "unmarked seams are passed through as the same instances");
	}

	@Test
	void two_factories_pick_the_first_by_name_and_keep_order() {
		List<ScannedSpi> marked = FactoryProducts.mark(
				List.of(seam(BASE + "IThing"), seam(BASE + "IZetaFactory"), seam(BASE + "IAlphaFactory")),
				Map.of(BASE + "IZetaFactory", Set.of(BASE + "IThing"), BASE + "IAlphaFactory", Set.of(BASE + "IThing")));

		assertEquals(List.of("IThing", "IZetaFactory", "IAlphaFactory"),
				marked.stream().map(ScannedSpi::name).collect(Collectors.toList()));
		assertEquals(BASE + "IAlphaFactory", byName(marked, "IThing").producedBy());
	}
}
