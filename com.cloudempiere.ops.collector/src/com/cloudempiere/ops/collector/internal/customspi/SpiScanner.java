/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.List;

/**
 * Source of the current SPI catalog for {@link CustomSpiHealthSource} to poll against. Split out
 * from {@link SpiPackageScanner} (the real OSGi/ClassGraph implementation) purely so
 * {@code CustomSpiHealthSourceTest} can inject a fixed fake list without needing a live OSGi
 * framework -- the real bundle-wiring/ClassGraph mechanics are covered separately in the
 * {@code .itest} module, the same split {@code SpiRegistryLiveResolutionTest} used before it.
 */
interface SpiScanner {
	List<ScannedSpi> scan();
}
