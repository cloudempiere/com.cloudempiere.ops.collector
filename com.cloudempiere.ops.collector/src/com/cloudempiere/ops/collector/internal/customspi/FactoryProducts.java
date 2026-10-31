/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the scanned interfaces that are factory products rather than seams: an interface some
 * other scanned interface returns from a method, e.g. {@code IDMS} from
 * {@code IDMSFactory.get()} or {@code IPDFFontMapper} from
 * {@code IPDFFontMapperFactory.createFontMapper()}. Nothing registers a product as an OSGi
 * service, so counting it as required kept readiness red for good; its factory is scanned and
 * gates readiness instead.
 * <p>
 * Kept free of ClassGraph so the plain-Maven test module can run it.
 *
 * @author CloudEmpiere
 */
final class FactoryProducts {

	private FactoryProducts() {
	}

	/**
	 * @param seams every interface found by the scan, across all {@code .spi} packages
	 * @param returnTypesByFqcn interface FQCN → FQCNs its declared methods return
	 * @return {@code seams} in the same order, each product marked with the first factory (by
	 *         FQCN) that returns it
	 */
	static List<ScannedSpi> mark(List<ScannedSpi> seams, Map<String, Set<String>> returnTypesByFqcn) {
		Map<String, String> factoryByProduct = new TreeMap<>();
		for (ScannedSpi factory : seams) {
			Set<String> returned = returnTypesByFqcn.get(factory.interfaceFqcn());
			if (returned == null) continue;
			for (String product : returned) {
				if (product.equals(factory.interfaceFqcn())) continue; // a fluent self-return is no product
				factoryByProduct.merge(product, factory.interfaceFqcn(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
			}
		}

		List<ScannedSpi> marked = new ArrayList<>(seams.size());
		for (ScannedSpi seam : seams) {
			String factory = factoryByProduct.get(seam.interfaceFqcn());
			marked.add(factory == null ? seam : seam.withProducedBy(factory));
		}
		return marked;
	}
}
