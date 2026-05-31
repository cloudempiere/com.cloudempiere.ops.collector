/******************************************************************************
 * Product: CloudEmpiere Ops REST                                              *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 *****************************************************************************/
package com.cloudempiere.ops.rest;

import java.util.HashSet;
import java.util.Set;

import org.osgi.service.component.annotations.Component;

import com.trekglobal.idempiere.rest.api.ResourceExtension;

/**
 * Registers the {@code /ops/v1} JAX-RS resource with the iDempiere REST API via the
 * {@code ResourceExtension} SPI (ADR-001 §4.7) — the same mechanism as
 * {@code com.cloudempiere.rest.cache}'s {@code CacheResourceExtension}. If this bundle is not
 * deployed, the REST API works unchanged.
 *
 * @author CloudEmpiere
 */
@Component(service = ResourceExtension.class, immediate = true)
public class OpsResourceExtension implements ResourceExtension {

	@Override
	public Set<Class<?>> getResourceClasses() {
		Set<Class<?>> resources = new HashSet<>();
		resources.add(OpsV1Resource.class);
		return resources;
	}
}
